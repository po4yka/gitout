package com.jakewharton.gitout

import com.akuleshov7.ktoml.Toml
import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable

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
		private val format = Toml

		fun parse(toml: String): Config {
			return format.decodeFromString(serializer(), toml)
		}
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
		@kotlinx.serialization.SerialName("notify_completion")
		val notifyCompletion: Boolean = true,
		@kotlinx.serialization.SerialName("notify_errors")
		val notifyErrors: Boolean = true,
		@kotlinx.serialization.SerialName("notify_new_repos")
		val notifyNewRepos: Boolean = true,
		@kotlinx.serialization.SerialName("allowed_users")
		val allowedUsers: List<Long> = emptyList(),
		@kotlinx.serialization.SerialName("enable_commands")
		val enableCommands: Boolean = false,
	)
}
