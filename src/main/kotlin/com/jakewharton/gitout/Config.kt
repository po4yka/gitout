package com.jakewharton.gitout

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.parsers.TomlParser
import com.akuleshov7.ktoml.tree.nodes.TomlArrayOfTablesElement
import com.akuleshov7.ktoml.tree.nodes.TomlKeyValueArray
import com.akuleshov7.ktoml.tree.nodes.TomlKeyValuePrimitive
import com.akuleshov7.ktoml.tree.nodes.TomlNode
import com.akuleshov7.ktoml.tree.nodes.TomlTable
import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.exists

internal const val DEFAULT_TELEGRAM_PROGRESS_STEP_PERCENT = 10

@Poko
@Serializable
internal class Config(
	val version: Int,
	val github: GitHub? = null,
	val git: Git = Git(),
	val ssl: Ssl = Ssl(),
	val parallelism: Parallelism = Parallelism(),
	val metrics: MetricsConfig = MetricsConfig(),
	val telegram: Telegram? = null,
) {
	companion object {
		private val lenientToml = Toml(TomlInputConfig(ignoreUnknownNames = true))
		private val configSchema = buildConfigSchema()

		private val allowedPaths = configSchema.allowedPaths
		private val wildcardPrefixes = configSchema.wildcardPrefixes

		fun parse(toml: String, logger: Logger? = null): Config {
			logger?.let { logUnknownOptions(toml, it) }
			return lenientToml.decodeFromString(serializer(), toml)
		}

		private fun logUnknownOptions(toml: String, logger: Logger) {
			val tomlFile = runCatching { TomlParser(TomlInputConfig()).parseString(toml) }.getOrElse { return }
			val unknownOptions = findUnknownOptions(tomlFile)
			if (unknownOptions.isEmpty()) {
				return
			}
			for (option in unknownOptions) {
				val scopeText = option.scope?.let { " in [$it]" } ?: ""
				val locationText = if (option.line > 0) " (line ${option.line})" else ""
				logger.warn("Unknown config option '${option.path}'$scopeText$locationText. This option will be ignored.")
			}
		}

		private fun findUnknownOptions(root: TomlNode): Collection<ConfigOptionPath> {
			val discovered = mutableListOf<ConfigOptionPath>()
			traverseNodes(root, null, discovered)
			val unknownByPath = LinkedHashMap<String, ConfigOptionPath>()
			for (option in discovered) {
				if (option.path.isBlank()) {
					continue
				}
				if (isAllowedPath(option.path)) {
					continue
				}
				unknownByPath.putIfAbsent(option.path, option)
			}
			return unknownByPath.values
		}

		private fun traverseNodes(node: TomlNode, currentScope: String?, result: MutableList<ConfigOptionPath>) {
			when (node) {
				is TomlTable -> {
					val nextScope = node.fullTableKey.toString().trim().ifEmpty { currentScope }
					node.children.forEach { child ->
						traverseNodes(child, nextScope, result)
					}
				}
				is TomlArrayOfTablesElement -> node.children.forEach { child ->
					traverseNodes(child, currentScope, result)
				}
				is TomlKeyValuePrimitive -> result += node.toConfigOption(currentScope)
				is TomlKeyValueArray -> result += node.toConfigOption(currentScope)
				else -> node.children.forEach { child ->
					traverseNodes(child, currentScope, result)
				}
			}
		}

		private fun TomlNode.toConfigOption(scope: String?): ConfigOptionPath {
			val keyName = this.name.trim()
			val scopeName = scope?.takeIf { it.isNotBlank() }
			val path = when {
				scopeName.isNullOrBlank() -> keyName
				keyName.isBlank() -> scopeName
				else -> "$scopeName.$keyName"
			}
			return ConfigOptionPath(
				path = path,
				scope = scopeName,
				line = lineNo,
			)
		}

		private fun isAllowedPath(path: String): Boolean {
			if (path in allowedPaths) {
				return true
			}
			return wildcardPrefixes.any { prefix ->
				path == prefix || path.startsWith("$prefix.")
			}
		}

		private fun buildConfigSchema(): ConfigSchema {
			val descriptor = serializer().descriptor
			val allowed = mutableSetOf<String>()
			val wildcards = mutableSetOf<String>()

			fun collect(desc: SerialDescriptor, prefix: String?) {
				for (index in 0 until desc.elementsCount) {
					val name = desc.getElementName(index)
					val currentPath = if (prefix.isNullOrEmpty()) name else "$prefix.$name"
					allowed += currentPath
					val childDescriptor = desc.getElementDescriptor(index)
					when (childDescriptor.kind) {
						StructureKind.CLASS,
						StructureKind.OBJECT -> collect(childDescriptor, currentPath)
						StructureKind.LIST -> {
							if (childDescriptor.elementsCount > 0) {
								val elementDescriptor = childDescriptor.getElementDescriptor(0)
								if (elementDescriptor.kind == StructureKind.CLASS || elementDescriptor.kind == StructureKind.OBJECT) {
									collect(elementDescriptor, currentPath)
								}
							}
						}
						StructureKind.MAP -> wildcards += currentPath
						else -> {}
					}
				}
			}

			collect(descriptor, null)
			return ConfigSchema(
				allowedPaths = allowed,
				wildcardPrefixes = wildcards,
			)
		}

		private data class ConfigSchema(
			val allowedPaths: Set<String>,
			val wildcardPrefixes: Set<String>,
		)

		private data class ConfigOptionPath(
			val path: String,
			val scope: String?,
			val line: Int,
		)
	}

	/**
	 * Validates the configuration and returns a list of validation errors.
	 * Returns an empty list if the configuration is valid.
	 */
	fun validate(): List<ValidationError> {
		val errors = mutableListOf<ValidationError>()

		// Validate version
                if (version < 0) {
			errors.add(ValidationError.InvalidVersion(version))
		}

		// Validate GitHub configuration
		github?.let { gh ->
			if (gh.user.isBlank()) {
				errors.add(ValidationError.EmptyGitHubUser)
			}

			// Validate that at least one clone option is enabled or repos are specified
			if (!gh.clone.starred && !gh.clone.watched && !gh.clone.gists && gh.clone.repos.isEmpty()) {
				errors.add(ValidationError.NoGitHubCloneOptionsEnabled)
			}
		}

		// Validate Git repositories URLs
		git.repos.forEach { (name, url) ->
			if (name.isBlank()) {
				errors.add(ValidationError.EmptyGitRepoName(url))
			} else if (!isValidRepositoryName(name)) {
				// Check for path traversal and invalid characters
				errors.add(ValidationError.InvalidRepositoryName(name))
			}
			if (url.isBlank()) {
				errors.add(ValidationError.EmptyGitRepoUrl(name))
			}
			if (!isValidGitUrl(url)) {
				errors.add(ValidationError.InvalidGitUrl(name, url))
			}
		}

		// Validate SSL configuration
		ssl.certFile?.let { certPath ->
			if (certPath.isNotBlank() && !Paths.get(certPath).exists()) {
				errors.add(ValidationError.CertFileNotFound(certPath))
			}
		}

		// Validate parallelism configuration
		if (parallelism.workers < 1) {
			errors.add(ValidationError.InvalidWorkerCount(parallelism.workers))
		}
		if (parallelism.workers > 32) {
			errors.add(ValidationError.TooManyWorkers(parallelism.workers))
		}
		if (parallelism.progressIntervalMs < 100) {
			errors.add(ValidationError.InvalidProgressInterval(parallelism.progressIntervalMs))
		}
		parallelism.repositoryTimeoutSeconds?.let { timeout ->
			if (timeout < 1) {
				errors.add(ValidationError.InvalidRepositoryTimeout(timeout))
			}
		}

		// Validate priority patterns
		parallelism.priorities.forEach { pattern ->
			if (pattern.pattern.isBlank()) {
				errors.add(ValidationError.EmptyPriorityPattern)
			}
			pattern.timeout?.let { timeout ->
				if (timeout < 1) {
					errors.add(ValidationError.InvalidPriorityTimeout(pattern.pattern, timeout))
				}
			}
		}

		// Validate metrics configuration
		if (metrics.format !in listOf("console", "json", "csv")) {
			errors.add(ValidationError.InvalidMetricsFormat(metrics.format))
		}
		metrics.exportPath?.let { path ->
			if (path.isBlank()) {
				errors.add(ValidationError.EmptyMetricsExportPath)
			}
		}

		// Validate Telegram configuration
                telegram?.let { tg ->
                        if (tg.chatId.isBlank()) {
                                errors.add(ValidationError.EmptyTelegramChatId)
                        }
                        if (tg.notifyProgressStepPercent !in 1..100) {
                                errors.add(ValidationError.InvalidTelegramProgressStep(tg.notifyProgressStepPercent))
                        }
                }

		return errors
	}

	private fun isValidGitUrl(url: String): Boolean {
		// Basic validation for git URLs
		return url.matches(Regex("^(https?://|git@|git://|ssh://|file://).*")) ||
			url.matches(Regex("^[\\w.-]+@[\\w.-]+:.*"))
	}

	private fun isValidRepositoryName(name: String): Boolean {
		// Prevent path traversal attacks and ensure safe filesystem names
		// Allow only alphanumeric characters, dots, underscores, hyphens, and forward slashes for org/repo format
		// Disallow: "..", leading/trailing slashes, consecutive slashes, and other special characters
		if (name.contains("..")) return false
		if (name.startsWith("/") || name.endsWith("/")) return false
		if (name.contains("//")) return false
		return name.matches(Regex("^[a-zA-Z0-9._/-]+$"))
	}

	@Poko
	@Serializable
	class GitHub(
		val user: String,
		val token: String? = null,
		val archive: Archive = Archive(),
		val clone: Clone = Clone(),
	) {
		@Poko
		@Serializable
		class Archive(
			val owned: Boolean = false,
		)
		@Poko
		@Serializable
		class Clone(
			val starred: Boolean = false,
			val watched: Boolean = false,
			val gists: Boolean = true,
			val repos: List<String> = emptyList(),
			val ignore: List<String> = emptyList(),
		)
	}

	@Poko
	@Serializable
	class Git(
		val repos: Map<String, String> = emptyMap(),
	)

	@Poko
	@Serializable
	class Ssl(
		@kotlinx.serialization.SerialName("cert_file")
		val certFile: String? = null,
		@kotlinx.serialization.SerialName("verify_certificates")
		val verifyCertificates: Boolean = true,
	)

	@Poko
	@Serializable
	class Parallelism(
		val workers: Int = 4,
		@kotlinx.serialization.SerialName("progress_interval_ms")
		val progressIntervalMs: Long = 1000,
		@kotlinx.serialization.SerialName("repository_timeout_seconds")
		val repositoryTimeoutSeconds: Int? = null,
		val priorities: List<PriorityPattern> = emptyList(),
	)

	@Poko
	@Serializable
	class PriorityPattern(
		val pattern: String,
		val priority: Int,
		val timeout: Int? = null,
	)

	@Poko
	@Serializable
	class MetricsConfig(
		val enabled: Boolean = true,
		val format: String = "console",
		@kotlinx.serialization.SerialName("export_path")
		val exportPath: String? = null,
	)

        @Poko
        @Serializable
        class Telegram(
                val token: String? = null,
                @kotlinx.serialization.SerialName("chat_id")
                val chatId: String,
                val enabled: Boolean = true,
		@kotlinx.serialization.SerialName("notify_start")
		val notifyStart: Boolean = true,
                @kotlinx.serialization.SerialName("notify_progress")
                val notifyProgress: Boolean = true,
                @kotlinx.serialization.SerialName("notify_progress_step_percent")
                val notifyProgressStepPercent: Int = DEFAULT_TELEGRAM_PROGRESS_STEP_PERCENT,
                @kotlinx.serialization.SerialName("notify_completion")
                val notifyCompletion: Boolean = true,
                @kotlinx.serialization.SerialName("notify_errors")
                val notifyErrors: Boolean = true,
                @kotlinx.serialization.SerialName("notify_new_repos")
                val notifyNewRepos: Boolean = true,
                @kotlinx.serialization.SerialName("notify_updates")
                val notifyUpdates: Boolean = false,
                @kotlinx.serialization.SerialName("allowed_users")
                @Serializable(with = TelegramUserIdListSerializer::class)
                val allowedUsers: List<Long> = emptyList(),
                @kotlinx.serialization.SerialName("enable_commands")
                val enableCommands: Boolean = false,
                @kotlinx.serialization.SerialName("notify_only_repos")
                val notifyOnlyRepos: List<String> = emptyList(),
                @kotlinx.serialization.SerialName("notify_ignore_repos")
                val notifyIgnoreRepos: List<String> = emptyList(),
                @kotlinx.serialization.SerialName("error_keywords")
                val errorKeywords: Map<String, List<String>> = emptyMap(),
        )
}

/**
 * Represents a validation error in the configuration.
 */
internal sealed class ValidationError {
	internal abstract val message: String

	internal data class InvalidVersion(val version: Int) : ValidationError() {
		override val message = "Invalid version: $version. Version must be >= 1."
	}

	object EmptyGitHubUser : ValidationError() {
		override val message = "GitHub user cannot be empty."
	}

	object NoGitHubCloneOptionsEnabled : ValidationError() {
		override val message = "At least one GitHub clone option must be enabled (starred, watched, gists) or repos must be specified."
	}

	data class EmptyGitRepoName(val url: String) : ValidationError() {
		override val message = "Git repository name cannot be empty for URL: $url"
	}

	data class EmptyGitRepoUrl(val name: String) : ValidationError() {
		override val message = "Git repository URL cannot be empty for repository: $name"
	}

	data class InvalidGitUrl(val name: String, val url: String) : ValidationError() {
		override val message = "Invalid git URL for repository '$name': $url"
	}

	data class InvalidRepositoryName(val name: String) : ValidationError() {
		override val message = "Invalid repository name '$name': contains path traversal characters or invalid characters. " +
			"Names must match [a-zA-Z0-9._-]+"
	}

	data class CertFileNotFound(val path: String) : ValidationError() {
		override val message = "SSL certificate file not found: $path"
	}

	data class InvalidWorkerCount(val count: Int) : ValidationError() {
		override val message = "Invalid worker count: $count. Must be >= 1."
	}

	data class TooManyWorkers(val count: Int) : ValidationError() {
		override val message = "Too many workers: $count. Maximum is 32."
	}

	data class InvalidProgressInterval(val interval: Long) : ValidationError() {
		override val message = "Invalid progress interval: $interval ms. Must be >= 100."
	}

	data class InvalidRepositoryTimeout(val timeout: Int) : ValidationError() {
		override val message = "Invalid repository timeout: $timeout seconds. Must be >= 1."
	}

	object EmptyPriorityPattern : ValidationError() {
		override val message = "Priority pattern cannot be empty."
	}

	data class InvalidPriorityTimeout(val pattern: String, val timeout: Int) : ValidationError() {
		override val message = "Invalid timeout for priority pattern '$pattern': $timeout seconds. Must be >= 1."
	}

	data class InvalidMetricsFormat(val format: String) : ValidationError() {
		override val message = "Invalid metrics format: $format. Must be one of: console, json, csv."
	}

        object EmptyMetricsExportPath : ValidationError() {
                override val message = "Metrics export path cannot be empty when specified."
        }

        object EmptyTelegramChatId : ValidationError() {
                override val message = "Telegram chat_id cannot be empty."
        }

        data class InvalidTelegramProgressStep(val step: Int) : ValidationError() {
                override val message = "Invalid Telegram progress step percent: $step. Must be between 1 and 100."
        }

}
