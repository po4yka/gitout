package com.jakewharton.gitout

import java.lang.ProcessBuilder.Redirect.INHERIT
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

internal class Engine(
	private val config: Path,
	private val destination: Path,
	private val timeout: Duration,
	private val workers: Int?,
	private val logger: Logger,
	private val client: OkHttpClient,
	private val healthCheck: HealthCheck?,
	private val telegramService: TelegramNotificationService?,
) {
	private var sslConfig: Config.Ssl = Config.Ssl()
	private var sslEnvironment: Map<String, String> = emptyMap()

	// Retry policy for git sync operations
	private val retryPolicy = RetryPolicy(
		maxAttempts = 6,
		baseDelayMs = 5000L,
		backoffStrategy = RetryPolicy.BackoffStrategy.LINEAR,
		logger = logger,
	)

	/**
	 * Represents a single repository synchronization task.
	 */
	private data class SyncTask(
		val name: String,
		val url: String,
		val destination: Path,
		val credentials: Path?,
		val reasons: Set<String>? = null,
		val isNewRepo: Boolean = false,
	)

	/**
	 * Represents the result of a repository synchronization operation.
	 */
	private data class SyncOperationResult(
		val hasChanges: Boolean = false,
		val commitCount: Int = 0,
	)

	/**
	 * Resolves the GitHub token from multiple sources with the following priority:
	 * 1. config.toml token (if present)
	 * 2. GITHUB_TOKEN_FILE environment variable (path to file containing token)
	 * 3. GITHUB_TOKEN environment variable (token value directly)
	 */
        private fun resolveGitHubToken(configToken: String?): String {
                // Priority 1: Use token from config.toml if present
                val tokenFromConfig = configToken?.trim()
                if (!tokenFromConfig.isNullOrEmpty()) {
                        logger.debug { "Using GitHub token from config.toml" }
                        return tokenFromConfig
                } else if (configToken != null) {
                        logger.warn("GitHub token in config.toml is blank. Falling back to environment variables.")
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
                val tokenEnv = System.getenv("GITHUB_TOKEN")?.trim()
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

		val config = Config.parse(config.readText(), logger)
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

		// Determine worker pool size: CLI option > config.toml > default (4)
		val workerPoolSize = workers ?: config.parallelism.workers
		logger.info { "Using $workerPoolSize parallel workers for synchronization" }

		// Collect all sync tasks
		val syncTasks = mutableListOf<SyncTask>()
		var githubCredentials: Path? = null

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

			// Track repository state changes
			val stateFile = githubDestination.resolve(".gitout-state.json")
			val stateTracker = RepositoryStateTracker(stateFile, logger)
			val repositoryChanges = stateTracker.detectChanges(githubRepositories.metadata)

			// Notify about repository changes (archived, deleted, visibility changes)
			if (repositoryChanges.hasChanges()) {
				logger.info { "Detected ${repositoryChanges.totalChanges()} repository state change(s)" }
				telegramService.safeNotify("repository changes") {
					notifyRepositoryChanges(repositoryChanges)
				}
			}

			// Save the current state (will be updated after sync completes)
			if (!dryRun) {
				stateTracker.saveState(githubRepositories.metadata)
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

			githubCredentials = if (dryRun) {
				null
			} else {
				Files.createTempFile("gitout-credentials-", "").apply {
					// Ensure cleanup on JVM exit as a backup
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
				syncTasks.add(SyncTask(nameAndOwner, repoUrl, repoDestination, githubCredentials, reasons))
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
					syncTasks.add(SyncTask("gist:$gist", gistUrl, gistDestination, githubCredentials))
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
			syncTasks.add(SyncTask(name, url, repoDestination, null))
		}

		// Detect new repositories and mark them in the sync tasks
		if (!dryRun) {
			val updatedSyncTasks = mutableListOf<SyncTask>()
			val newRepos = mutableListOf<String>()

			for (task in syncTasks) {
				val isNew = task.destination.notExists() && (
					task.reasons?.contains("starred") == true ||
					task.reasons?.contains("watching") == true
				)

				if (isNew) {
					newRepos.add(task.name)
					updatedSyncTasks.add(task.copy(isNewRepo = true))
				} else {
					updatedSyncTasks.add(task)
				}
			}

			if (newRepos.isNotEmpty()) {
				logger.info { "Detected ${newRepos.size} new repositories to backup" }
                                telegramService.safeNotify("new repositories") {
                                        notifyNewRepositories(newRepos)
                                }
			}

			// Replace syncTasks with the updated list
			syncTasks.clear()
			syncTasks.addAll(updatedSyncTasks)
		}

		// Execute all sync tasks in parallel with worker pool
		// Use try-finally to guarantee credentials cleanup even on exceptions
		try {
			executeSyncTasksInParallel(syncTasks, workerPoolSize, dryRun)
		} finally {
			// Clean up credentials file immediately after sync completes (or fails)
			githubCredentials?.let { credPath ->
				try {
					Files.deleteIfExists(credPath)
					logger.debug { "Cleaned up credentials file" }
				} catch (e: Exception) {
					logger.warn("Failed to delete credentials file: ${e.message}")
				}
			}
		}

		startedHealthCheck?.complete()
	}

	/**
	 * Executes sync tasks in parallel using a worker pool with a semaphore to limit concurrency.
	 * Collects all results and reports failures at the end.
	 */
	private suspend fun executeSyncTasksInParallel(
		tasks: List<SyncTask>,
		workerPoolSize: Int,
		dryRun: Boolean,
	) {
		if (tasks.isEmpty()) {
			logger.info { "No repositories to synchronize" }
			return
		}

		logger.info { "Starting synchronization of ${tasks.size} repositories with $workerPoolSize workers" }

		// Send Telegram notification about sync start
                telegramService.safeNotify("sync start") {
                        notifySyncStart(tasks.size, workerPoolSize)
                }

		// Track results and timing
		data class SyncResult(
			val task: SyncTask,
			val success: Boolean,
			val error: Throwable? = null,
			val durationMs: Long = 0,
		)
		val results = mutableListOf<SyncResult>()
		val startTime = System.currentTimeMillis()

		// Create semaphore to limit concurrent operations
		val semaphore = Semaphore(workerPoolSize)

		// Execute all tasks in parallel with worker pool limit
		coroutineScope {
			tasks.map { task ->
				async(Dispatchers.IO) {
					semaphore.withPermit {
						val taskStartTime = System.currentTimeMillis()
						try {
							val reasonsStr = task.reasons?.let { " because $it" } ?: ""
							logger.lifecycle { "Starting sync: ${task.url}$reasonsStr" }

							val syncResult = syncBare(task.destination, task.url, dryRun, task.credentials)

							val taskDuration = System.currentTimeMillis() - taskStartTime
							logger.lifecycle { "Completed sync: ${task.url}" }

							// Notify about first-time backup completion
                                                        if (task.isNewRepo && !dryRun) {
                                                                telegramService.safeNotify("first backup") {
                                                                        notifyFirstBackup(task.name, task.url)
                                                                }
                                                        } else if (!dryRun && syncResult.hasChanges) {
                                                                // Notify about regular repository update only if there were changes
                                                                telegramService.safeNotify("repository update") {
                                                                        notifyRepositoryUpdate(task.name, task.url, syncResult.commitCount)
                                                                }
                                                        }

							SyncResult(task, success = true, durationMs = taskDuration)
						} catch (e: Throwable) {
							val taskDuration = System.currentTimeMillis() - taskStartTime
							val errorMsg = e.message ?: "Unknown error"
							logger.warn("Failed sync: ${task.url}: $errorMsg")

							// Send immediate error notification
                                                        if (!dryRun) {
                                                                telegramService.safeNotify("sync error") {
                                                                        notifySyncError(task.name, task.url, errorMsg)
                                                                }
                                                        }

							SyncResult(task, success = false, error = e, durationMs = taskDuration)
						}
					}
				}
			}.awaitAll().also { results.addAll(it) }
		}

		// Calculate duration
		val endTime = System.currentTimeMillis()
		val durationSeconds = (endTime - startTime) / 1000

		// Report summary
		val successful = results.count { it.success }
		val failed = results.count { !it.success }

		logger.info { "Synchronization complete: $successful succeeded, $failed failed" }

		// Send Telegram notification about completion
                telegramService.safeNotify("sync completion") {
                        notifySyncCompletion(successful, failed, durationSeconds)
                }

		if (failed > 0) {
			logger.warn("Failed repositories:")
			val failedRepos = mutableMapOf<String, String>()
			results.filter { !it.success }.forEach { result ->
				val errorMsg = result.error?.message ?: "Unknown error"
				logger.warn("  - ${result.task.name} (${result.task.url}): $errorMsg")
				failedRepos[result.task.name] = errorMsg
			}

			// Send Telegram notification about errors
                        telegramService.safeNotify("sync errors summary") {
                                notifyErrors(failedRepos)
                        }

			val errorMessage = "$failed out of ${tasks.size} repositories failed to synchronize"
			logger.warn(errorMessage)
			throw IllegalStateException(errorMessage)
		}
	}

	private fun setupSsl(ssl: Config.Ssl) {
		val envVars = mutableMapOf<String, String>()

		// Handle SSL certificate configuration for git subprocesses
		// Note: We do NOT set javax.net.ssl.trustStore here because Java expects JKS/PKCS12 format,
		// not PEM files. Java uses its own default cacerts which works fine.
		val certFile = ssl.certFile ?: findDefaultCertFile()
		if (certFile != null) {
			logger.debug { "Using SSL certificate file for git: $certFile" }

			// Set environment variables for git subprocesses only
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

        private suspend fun syncBare(repo: Path, url: String, dryRun: Boolean, credentials: Path? = null): SyncOperationResult {
                val repoExists = repo.exists()

                // Handle dry run separately (no retry needed)
                if (dryRun) {
                        val command = buildGitCommand(repo, url, credentials, repoExists)
                        val directory = if (!repoExists) repo.parent else repo
			logger.lifecycle { "DRY RUN $directory ${command.joinToString(separator = " ")}" }
			return SyncOperationResult(hasChanges = false, commitCount = 0)
		}

		// Get the HEAD ref before sync (if repo exists)
                val beforeHeadRef = if (repoExists) {
			getHeadRef(repo)
		} else {
			null
		}

		// Use retry policy for actual sync operations
		retryPolicy.execute(operationDescription = url) { attempt ->
			executeSyncOperation(repo, url, credentials)
		}

		// Get the HEAD ref after sync
		val afterHeadRef = getHeadRef(repo)

		// Detect changes
		val hasChanges = beforeHeadRef == null || beforeHeadRef != afterHeadRef
		val commitCount = if (hasChanges && beforeHeadRef != null && afterHeadRef != null) {
			countCommitsBetween(repo, beforeHeadRef, afterHeadRef)
		} else if (hasChanges && beforeHeadRef == null) {
			// New repository - count all commits
			countTotalCommits(repo)
		} else {
			0
		}

		logger.debug {
			if (hasChanges) {
				"Repository ${repo.name} has changes: $commitCount new commit(s)"
			} else {
				"Repository ${repo.name} has no changes"
			}
		}

		return SyncOperationResult(hasChanges = hasChanges, commitCount = commitCount)
	}

	/**
	 * Builds the git command for syncing a repository.
	 */
        private fun buildGitCommand(
                repo: Path,
                url: String,
                credentials: Path?,
                repoExists: Boolean,
        ): List<String> {
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

                if (!repoExists) {
			command.apply {
				add("clone")
				add("--mirror")
				add(url)
				add(repo.name)
			}
		} else {
			command.apply {
				add("remote")
				add("update")
				add("--prune")
			}
		}

		return command
	}

	/**
	 * Gets the current HEAD ref of a repository.
	 * Returns null if the operation fails or the repository is empty.
	 */
	private fun getHeadRef(repo: Path): String? {
		return try {
			val process = ProcessBuilder()
				.command("git", "-C", repo.absolutePathString(), "rev-parse", "HEAD")
				.redirectError(ProcessBuilder.Redirect.PIPE)
				.redirectOutput(ProcessBuilder.Redirect.PIPE)
				.start()

			if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
				logger.warn("Git rev-parse timed out for ${repo.name}")
				process.destroyForcibly()
				return null
			}

			val exitCode = process.exitValue()
			if (exitCode == 0) {
				process.inputStream.bufferedReader().use { it.readText().trim() }
			} else {
				// Repository might be empty or have no commits
				logger.debug { "Could not get HEAD ref for ${repo.name} (exit code: $exitCode)" }
				null
			}
		} catch (e: Exception) {
			logger.debug { "Error getting HEAD ref for ${repo.name}: ${e.message}" }
			null
		}
	}

	/**
	 * Counts the number of commits between two refs.
	 * Returns 0 if the operation fails.
	 */
	private fun countCommitsBetween(repo: Path, beforeRef: String, afterRef: String): Int {
		return try {
			val process = ProcessBuilder()
				.command("git", "-C", repo.absolutePathString(), "rev-list", "--count", "$beforeRef..$afterRef")
				.redirectError(ProcessBuilder.Redirect.PIPE)
				.redirectOutput(ProcessBuilder.Redirect.PIPE)
				.start()

			if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
				logger.warn("Git rev-list timed out for ${repo.name}")
				process.destroyForcibly()
				return 0
			}

			val exitCode = process.exitValue()
			if (exitCode == 0) {
				process.inputStream.bufferedReader().use { it.readText().trim().toIntOrNull() ?: 0 }
			} else {
				logger.debug { "Could not count commits for ${repo.name} (exit code: $exitCode)" }
				0
			}
		} catch (e: Exception) {
			logger.debug { "Error counting commits for ${repo.name}: ${e.message}" }
			0
		}
	}

	/**
	 * Counts the total number of commits in a repository.
	 * Returns 0 if the operation fails.
	 */
	private fun countTotalCommits(repo: Path): Int {
		return try {
			val process = ProcessBuilder()
				.command("git", "-C", repo.absolutePathString(), "rev-list", "--count", "--all")
				.redirectError(ProcessBuilder.Redirect.PIPE)
				.redirectOutput(ProcessBuilder.Redirect.PIPE)
				.start()

			if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
				logger.warn("Git rev-list timed out for ${repo.name}")
				process.destroyForcibly()
				return 0
			}

			val exitCode = process.exitValue()
			if (exitCode == 0) {
				process.inputStream.bufferedReader().use { it.readText().trim().toIntOrNull() ?: 0 }
			} else {
				logger.debug { "Could not count total commits for ${repo.name} (exit code: $exitCode)" }
				0
			}
		} catch (e: Exception) {
			logger.debug { "Error counting total commits for ${repo.name}: ${e.message}" }
			0
		}
	}

	/**
	 * Executes a single git sync operation.
	 * Throws an exception if the operation fails.
	 */
        private fun executeSyncOperation(repo: Path, url: String, credentials: Path?) {
                val repoExistsBeforeCommand = repo.exists()
                val command = buildGitCommand(repo, url, credentials, repoExistsBeforeCommand)

                val directory = if (!repoExistsBeforeCommand) {
                        repo.parent.apply {
                                createDirectories()
                        }
                } else {
                        repo
                }

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

                try {
                        if (!process.waitFor(timeout)) {
                                logger.warn("Git process timed out for $url, terminating process")
                                process.destroy()
                                if (process.isAlive) {
                                        logger.warn("Process did not terminate gracefully, force destroying")
                                        process.destroyForcibly()
                                        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                                }
                                throw IllegalStateException("Unable to sync $url into $repo: timeout $timeout")
                        }

                        val exitCode = process.exitValue()
                        if (exitCode == 0) {
                                logger.debug { "Successfully synced $url" }
                        } else {
                                throw IllegalStateException("Unable to sync $url into $repo: exit $exitCode")
                        }
                } catch (t: Throwable) {
                        if (!repoExistsBeforeCommand) {
                                cleanupIncompleteRepository(repo)
                        }
                        throw t
                }
        }

        private fun cleanupIncompleteRepository(repo: Path) {
                if (repo.notExists()) {
                        return
                }

                runCatching {
                        Files.walk(repo)
                                .use { stream ->
                                        stream.sorted(Comparator.reverseOrder()).forEach { path ->
                                                Files.deleteIfExists(path)
                                        }
                                }
                        logger.debug { "Removed incomplete repository at ${repo.absolutePathString()}" }
                }.onFailure { error ->
                        logger.warn("Failed to clean up incomplete repository at ${repo.absolutePathString()}: ${error.message}")
                        logger.debug { "Cleanup failure details: ${error.stackTraceToString()}" }
                }
        }

        private inline fun TelegramNotificationService?.safeNotify(
                actionDescription: String,
                block: TelegramNotificationService.() -> Unit,
        ) {
                if (this == null) {
                        return
                }

                runCatching { block() }
                        .onFailure { error ->
                                val message = error.message ?: "unknown error"
                                logger.warn("Failed to send Telegram $actionDescription notification: $message")
                                logger.debug { "Telegram notification failure ($actionDescription): ${error.stackTraceToString()}" }
                        }
        }
}
