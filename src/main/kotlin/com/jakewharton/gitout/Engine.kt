package com.jakewharton.gitout

import java.lang.ProcessBuilder.Redirect.INHERIT
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

internal class Engine(
	private val config: Path,
	private val destination: Path,
	private val timeout: Duration,
	private val logger: Logger,
	private val client: OkHttpClient,
	private val healthCheck: HealthCheck?,
) {
	private var sslConfig: Config.Ssl = Config.Ssl()
	private var sslEnvironment: Map<String, String> = emptyMap()

	/**
	 * Resolves the GitHub token from multiple sources with the following priority:
	 * 1. config.toml token (if present)
	 * 2. GITHUB_TOKEN_FILE environment variable (path to file containing token)
	 * 3. GITHUB_TOKEN environment variable (token value directly)
	 */
	private fun resolveGitHubToken(configToken: String?): String {
		// Priority 1: Use token from config.toml if present
		if (configToken != null) {
			logger.debug { "Using GitHub token from config.toml" }
			return configToken
		}

		// Priority 2: Check GITHUB_TOKEN_FILE environment variable
		val tokenFilePath = System.getenv("GITHUB_TOKEN_FILE")
		if (tokenFilePath != null) {
			val tokenFile = Paths.get(tokenFilePath)
			if (tokenFile.exists()) {
				val token = tokenFile.readText().trim()
				if (token.isNotEmpty()) {
					logger.debug { "Using GitHub token from GITHUB_TOKEN_FILE: $tokenFilePath" }
					return token
				} else {
					logger.warn("GITHUB_TOKEN_FILE is empty: $tokenFilePath")
				}
			} else {
				logger.warn("GITHUB_TOKEN_FILE does not exist: $tokenFilePath")
			}
		}

		// Priority 3: Check GITHUB_TOKEN environment variable
		val tokenEnv = System.getenv("GITHUB_TOKEN")
		if (tokenEnv != null && tokenEnv.isNotEmpty()) {
			logger.debug { "Using GitHub token from GITHUB_TOKEN environment variable" }
			return tokenEnv
		}

		// No token found anywhere
		throw IllegalStateException(
			"GitHub token not found. Please provide a token via:\n" +
			"  1. config.toml [github] token field\n" +
			"  2. GITHUB_TOKEN_FILE environment variable (path to file)\n" +
			"  3. GITHUB_TOKEN environment variable (token value)"
		)
	}

	suspend fun performSync(dryRun: Boolean) {
		val startedHealthCheck = healthCheck?.start()

		val config = Config.parse(config.readText())
		logger.trace { config.toString() }
		check(config.version == 0) {
			"Only version 0 of the config is supported at this time"
		}

		check(dryRun || destination.exists() && destination.isDirectory()) {
			"Destination must exist and must be a directory"
		}

		// Setup SSL certificates
		sslConfig = config.ssl
		setupSsl(config.ssl)

		if (config.github != null) {
			// Resolve GitHub token with priority: config.toml > GITHUB_TOKEN_FILE > GITHUB_TOKEN
			val token = resolveGitHubToken(config.github.token)
			val gitHub = GitHub(config.github.user, token, client, logger)
			val githubDestination = destination.resolve("github")

			logger.info { "Querying GitHub information for ${config.github.user}…" }
			val githubRepositories = gitHub.loadRepositories()
			logger.trace { githubRepositories.toString() }
			logger.info {
				"""
				|Repositories
				|  owned: ${githubRepositories.owned.size}
				|  starred: ${githubRepositories.starred.size}
				|  watching: ${githubRepositories.watching.size}
				|  gists: ${githubRepositories.gists.size}
				""".trimMargin()
			}

			val nameAndOwnerToReasons = mutableMapOf<String, MutableSet<String>>()

			for (nameAndOwner in githubRepositories.owned) {
				logger.debug { "Owned candidate $nameAndOwner" }
				nameAndOwnerToReasons.getOrPut(nameAndOwner, ::HashSet).add("owned")
			}

			for (nameAndOwner in config.github.clone.repos) {
				logger.debug { "Explicit candidate $nameAndOwner" }
				nameAndOwnerToReasons.getOrPut(nameAndOwner, ::HashSet).add("explicit")
			}

			if (config.github.clone.starred) {
				for (nameAndOwner in githubRepositories.starred) {
					logger.debug { "Starred candidate $nameAndOwner" }
					nameAndOwnerToReasons.getOrPut(nameAndOwner, ::HashSet).add("starred")
				}
			} else {
				logger.debug { "Starred disabled by config" }
			}

			if (config.github.clone.watched) {
				for (nameAndOwner in githubRepositories.watching) {
					logger.debug { "Watching candidate $nameAndOwner" }
					nameAndOwnerToReasons.getOrPut(nameAndOwner, ::HashSet).add("watching")
				}
			} else {
				logger.debug { "Watching disabled by config" }
			}

			for (ignore in config.github.clone.ignore) {
				val reasons = nameAndOwnerToReasons.remove(ignore)
				if (reasons != null) {
					logger.debug { "Ignoring $ignore, was $reasons" }
				} else {
					logger.warn("Unused ignore: $ignore")
				}
			}

			val credentials = if (dryRun) {
				null
			} else {
				Files.createTempFile("gitout-credentials-", "").apply {
					toFile().deleteOnExit()
					writeText(
						HttpUrl.Builder()
							.scheme("https")
							.username(config.github.user)
							.password(token)
							.host("github.com")
							.build()
							.toString(),
					)
				}
			}

			val cloneDestination = githubDestination.resolve("clone")
			for ((nameAndOwner, reasons) in nameAndOwnerToReasons) {
				val repoDestination = cloneDestination.resolve(nameAndOwner)
				val repoUrl = HttpUrl.Builder()
					.scheme("https")
					.host("github.com")
					.build()
					.resolve("$nameAndOwner.git")
					.toString()
				logger.lifecycle { "Synchronizing $repoUrl because $reasons…" }
				syncBare(repoDestination, repoUrl, dryRun, credentials)
			}

			if (config.github.clone.gists) {
				val gistsDestination = githubDestination.resolve("gists")
				for (gist in githubRepositories.gists) {
					val gistDestination = gistsDestination.resolve(gist)
					val gistUrl = HttpUrl.Builder()
						.scheme("https")
						.host("gist.github.com")
						.addPathSegment("$gist.git")
						.toString()
					logger.lifecycle { "Synchronizing $gistUrl…" }
					syncBare(gistDestination, gistUrl, dryRun, credentials)
				}
			} else {
				logger.debug { "Gists disabled by config" }
			}
		} else {
			logger.debug { "GitHub absent from config" }
		}

		val gitDestination = destination.resolve("git")
		for ((name, url) in config.git.repos) {
			val repoDestination = gitDestination.resolve(name)
			logger.lifecycle { "Synchronizing $name $url…" }
			syncBare(repoDestination, url, dryRun)
		}

		startedHealthCheck?.complete()
	}

	private fun setupSsl(ssl: Config.Ssl) {
		val envVars = mutableMapOf<String, String>()

		// Handle SSL certificate configuration
		val certFile = ssl.certFile ?: findDefaultCertFile()
		if (certFile != null) {
			logger.debug { "Using SSL certificate file: $certFile" }
			System.setProperty("javax.net.ssl.trustStore", certFile)

			// Set environment variables for git
			val certPath = Paths.get(certFile)
			if (certPath.exists()) {
				envVars["SSL_CERT_FILE"] = certFile
				logger.debug { "SSL_CERT_FILE will be set to: $certFile" }

				certPath.parent?.let { dir ->
					val dirPath = dir.absolutePathString()
					envVars["SSL_CERT_DIR"] = dirPath
					logger.debug { "SSL_CERT_DIR will be set to: $dirPath" }
				}
			}
		}

		if (!ssl.verifyCertificates) {
			logger.warn("SSL certificate verification is DISABLED. Use only for testing!")
			envVars["GIT_SSL_NO_VERIFY"] = "1"
			logger.debug { "GIT_SSL_NO_VERIFY will be set to: 1" }
		}

		sslEnvironment = envVars
	}

	private fun findDefaultCertFile(): String? {
		val candidates = listOf(
			"/etc/ssl/certs/ca-certificates.crt",
			"/etc/ssl/cert.pem",
			"/usr/lib/ssl/cert.pem",
			"/etc/pki/tls/certs/ca-bundle.crt",
		)

		for (candidate in candidates) {
			val path = Paths.get(candidate)
			if (path.exists()) {
				logger.debug { "Found default SSL certificate file: $candidate" }
				return candidate
			}
		}

		logger.debug { "No default SSL certificate file found" }
		return null
	}

	private fun syncBare(repo: Path, url: String, dryRun: Boolean, credentials: Path? = null) {
		val maxRetries = 6
		val retryDelayMs = 5000L

		for (attempt in 1..maxRetries) {
			try {
				if (attempt > 1) {
					logger.info { "Retry attempt $attempt/$maxRetries for $url" }
					Thread.sleep(retryDelayMs * attempt) // Staggered backoff
				}

				val command = mutableListOf("git")

				// Add SSL configuration
				if (!sslConfig.verifyCertificates) {
					command.add("-c")
					command.add("http.sslVerify=false")
				}

				if (credentials != null) {
					command.add("-c")
					command.add("""credential.helper=store --file=${credentials.absolutePathString()}""")
				}

				val directory = if (repo.notExists()) {
					command.apply {
						add("clone")
						add("--mirror")
						add(url)
						add(repo.name)
					}
					repo.parent.apply {
						createDirectories()
					}
				} else {
					command.apply {
						add("remote")
						add("update")
						add("--prune")
					}
					repo
				}

				if (dryRun) {
					logger.lifecycle { "DRY RUN $directory ${command.joinToString(separator = " ")}" }
					return
				} else {
					val processBuilder = ProcessBuilder()
						.command(command)
						.directory(directory.toFile())
						.redirectError(INHERIT)

					// Apply SSL environment variables to the subprocess
					if (sslEnvironment.isNotEmpty()) {
						processBuilder.environment().putAll(sslEnvironment)
						logger.debug { "Applied SSL environment variables: ${sslEnvironment.keys}" }
					}

					val process = processBuilder.start()

					if (!process.waitFor(timeout)) {
						throw IllegalStateException("Unable to sync $url into $repo: timeout $timeout")
					}

					val exitCode = process.exitValue()
					if (exitCode == 0) {
						logger.debug { "Successfully synced $url" }
						return
					} else {
						throw IllegalStateException("Unable to sync $url into $repo: exit $exitCode")
					}
				}
			} catch (e: Exception) {
				logger.warn("Attempt $attempt failed for $url: ${e.message}")

				if (attempt == maxRetries) {
					throw IllegalStateException("Failed to sync $url after $maxRetries attempts", e)
				}
			}
		}
	}
}
