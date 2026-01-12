package com.jakewharton.gitout

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Tracks repository sync failures across sessions.
 * Used for intelligent retry strategies and failure pattern detection.
 */
internal class FailureTracker(
	private val stateFile: Path,
	private val config: Config.FailureTrackingConfig,
	private val logger: Logger,
) {
	private val json = Json {
		prettyPrint = true
		ignoreUnknownKeys = true
	}

	private var state: FailureState = loadState()

	/**
	 * Loads failure state from disk or creates new empty state.
	 */
	private fun loadState(): FailureState {
		if (!config.enabled) {
			return FailureState()
		}

		return try {
			if (stateFile.exists()) {
				val content = stateFile.readText()
				json.decodeFromString<FailureState>(content)
			} else {
				FailureState()
			}
		} catch (e: Exception) {
			logger.warn("Failed to load failure state from $stateFile: ${e.message}")
			FailureState()
		}
	}

	/**
	 * Saves the current failure state to disk.
	 */
	fun saveState() {
		if (!config.enabled) return

		try {
			val content = json.encodeToString(state)
			stateFile.writeText(content)
			logger.debug { "Saved failure state to $stateFile" }
		} catch (e: Exception) {
			logger.warn("Failed to save failure state to $stateFile: ${e.message}")
		}
	}

	/**
	 * Records a successful sync for a repository.
	 * Resets the failure counter for the repository.
	 */
	fun recordSuccess(repoName: String) {
		if (!config.enabled) return

		val existing = state.repositories[repoName]
		if (existing != null) {
			// Keep the record but reset consecutive failures
			state = state.copy(
				repositories = state.repositories + (repoName to existing.copy(
					consecutiveFailures = 0,
					lastSuccessTimestamp = System.currentTimeMillis(),
				))
			)
			logger.debug { "Reset failure counter for $repoName after successful sync" }
		}
	}

	/**
	 * Records a sync failure for a repository.
	 */
	fun recordFailure(repoName: String, errorMessage: String, errorCategory: ErrorCategory) {
		if (!config.enabled) return

		val existing = state.repositories[repoName]
		val now = System.currentTimeMillis()

		val updatedRecord = if (existing != null) {
			existing.copy(
				consecutiveFailures = existing.consecutiveFailures + 1,
				totalFailures = existing.totalFailures + 1,
				lastFailureTimestamp = now,
				lastErrorMessage = errorMessage,
				lastErrorCategory = errorCategory.name,
				errorHistory = (existing.errorHistory + errorCategory.name).takeLast(10),
			)
		} else {
			RepositoryFailureRecord(
				name = repoName,
				consecutiveFailures = 1,
				totalFailures = 1,
				lastFailureTimestamp = now,
				lastSuccessTimestamp = null,
				lastErrorMessage = errorMessage,
				lastErrorCategory = errorCategory.name,
				errorHistory = listOf(errorCategory.name),
			)
		}

		state = state.copy(
			repositories = state.repositories + (repoName to updatedRecord)
		)

		if (updatedRecord.consecutiveFailures >= config.maxConsecutiveFailures) {
			logger.warn("Repository $repoName has failed ${updatedRecord.consecutiveFailures} times consecutively")
		}
	}

	/**
	 * Checks if a repository should be skipped based on failure history.
	 */
	fun shouldSkip(repoName: String): Boolean {
		if (!config.enabled || !config.autoSkipFailing) return false

		val record = state.repositories[repoName] ?: return false

		// Check if we've exceeded max consecutive failures
		if (record.consecutiveFailures < config.maxConsecutiveFailures) return false

		// Check if we're still in cooldown period
		val lastFailure = record.lastFailureTimestamp ?: return false
		val cooldownMs = config.failureCooldownHours * 60 * 60 * 1000L
		val timeSinceLastFailure = System.currentTimeMillis() - lastFailure

		if (timeSinceLastFailure < cooldownMs) {
			val hoursRemaining = (cooldownMs - timeSinceLastFailure) / (60 * 60 * 1000.0)
			logger.info { "Skipping $repoName due to ${record.consecutiveFailures} consecutive failures (cooldown: ${String.format("%.1f", hoursRemaining)}h remaining)" }
			return true
		}

		return false
	}

	/**
	 * Gets the failure record for a repository.
	 */
	fun getFailureRecord(repoName: String): RepositoryFailureRecord? {
		return state.repositories[repoName]
	}

	/**
	 * Gets all repositories that have failure records.
	 */
	fun getFailingRepositories(): List<RepositoryFailureRecord> {
		return state.repositories.values.filter { it.consecutiveFailures > 0 }
	}

	/**
	 * Determines the recommended clone strategy based on failure history.
	 */
	fun getRecommendedStrategy(repoName: String, repoSizeKb: Long?, largeRepoConfig: Config.LargeRepoConfig): CloneStrategy {
		val record = state.repositories[repoName]
		val isLargeRepo = repoSizeKb != null && repoSizeKb >= largeRepoConfig.sizeThresholdKb
		val isVeryLargeRepo = repoSizeKb != null && repoSizeKb >= largeRepoConfig.shallowCloneThresholdKb

		// Determine if we should use shallow clone based on failure history
		val useShallowClone = record != null &&
			record.consecutiveFailures >= largeRepoConfig.shallowCloneAfterFailures &&
			isVeryLargeRepo

		// Determine if we need HTTP/1.1 based on error history
		val useHttp1 = record != null &&
			record.errorHistory.any { it == ErrorCategory.HTTP2_ERROR.name }

		return CloneStrategy(
			useHttp1 = useHttp1,
			useShallowClone = useShallowClone,
			isLargeRepo = isLargeRepo,
			timeoutMultiplier = if (isLargeRepo) largeRepoConfig.timeoutMultiplier else 1.0,
			consecutiveFailures = record?.consecutiveFailures ?: 0,
		)
	}

	/**
	 * Cleans up old records for repositories that no longer exist or have been successful for a long time.
	 */
	fun cleanup(activeRepos: Set<String>, maxAgeMs: Long = 30 * 24 * 60 * 60 * 1000L) {
		if (!config.enabled) return

		val now = System.currentTimeMillis()
		val cleaned = state.repositories.filter { (name, record) ->
			// Keep if repository is still active
			if (name in activeRepos) return@filter true
			// Keep if has recent activity
			val lastActivity = maxOf(
				record.lastFailureTimestamp ?: 0,
				record.lastSuccessTimestamp ?: 0
			)
			now - lastActivity < maxAgeMs
		}

		if (cleaned.size != state.repositories.size) {
			val removed = state.repositories.size - cleaned.size
			logger.info { "Cleaned up $removed stale failure records" }
			state = state.copy(repositories = cleaned)
		}
	}
}

/**
 * Persistent state for failure tracking.
 */
@Serializable
internal data class FailureState(
	val version: Int = 1,
	val repositories: Map<String, RepositoryFailureRecord> = emptyMap(),
)

/**
 * Failure record for a single repository.
 */
@Serializable
@Poko
internal class RepositoryFailureRecord(
	val name: String,
	val consecutiveFailures: Int,
	val totalFailures: Int,
	val lastFailureTimestamp: Long?,
	val lastSuccessTimestamp: Long?,
	val lastErrorMessage: String?,
	val lastErrorCategory: String?,
	val errorHistory: List<String> = emptyList(),
)

/**
 * Recommended clone strategy based on failure history and repository characteristics.
 */
@Poko
internal class CloneStrategy(
	val useHttp1: Boolean,
	val useShallowClone: Boolean,
	val isLargeRepo: Boolean,
	val timeoutMultiplier: Double,
	val consecutiveFailures: Int,
)
