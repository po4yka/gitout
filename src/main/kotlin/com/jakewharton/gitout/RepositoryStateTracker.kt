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
 * Tracks repository state changes between syncs.
 */
internal class RepositoryStateTracker(
	private val stateFile: Path,
	private val logger: Logger,
) {
	private val json = Json {
		prettyPrint = true
		ignoreUnknownKeys = true
	}

	/**
	 * Detects changes between the previous and current repository states.
	 */
	fun detectChanges(
		currentRepos: Map<String, RepositoryMetadata>,
	): RepositoryChanges {
		val previousState = loadState()
		val previousRepos = previousState?.repositories ?: emptyMap()

		val archived = mutableListOf<RepositoryChange>()
		val unarchived = mutableListOf<RepositoryChange>()
		val deleted = mutableListOf<RepositoryChange>()
		val visibilityChanged = mutableListOf<RepositoryChange>()
		val newRepos = mutableListOf<RepositoryChange>()

		// Check for deleted and changed repositories
		for ((name, prevMeta) in previousRepos) {
			val currentMeta = currentRepos[name]
			if (currentMeta == null) {
				// Repository was deleted or became inaccessible
				deleted += RepositoryChange(
					name = name,
					changeType = ChangeType.DELETED,
					previousValue = null,
					currentValue = null,
					metadata = prevMeta,
				)
			} else {
				// Check for archive status change
				if (!prevMeta.isArchived && currentMeta.isArchived) {
					archived += RepositoryChange(
						name = name,
						changeType = ChangeType.ARCHIVED,
						previousValue = "active",
						currentValue = "archived",
						metadata = currentMeta,
					)
				} else if (prevMeta.isArchived && !currentMeta.isArchived) {
					unarchived += RepositoryChange(
						name = name,
						changeType = ChangeType.UNARCHIVED,
						previousValue = "archived",
						currentValue = "active",
						metadata = currentMeta,
					)
				}

				// Check for visibility change
				if (prevMeta.visibility != currentMeta.visibility) {
					visibilityChanged += RepositoryChange(
						name = name,
						changeType = ChangeType.VISIBILITY_CHANGED,
						previousValue = prevMeta.visibility,
						currentValue = currentMeta.visibility,
						metadata = currentMeta,
					)
				}
			}
		}

		// Check for new repositories (only if we had previous state)
		if (previousRepos.isNotEmpty()) {
			for ((name, currentMeta) in currentRepos) {
				if (name !in previousRepos) {
					newRepos += RepositoryChange(
						name = name,
						changeType = ChangeType.NEW,
						previousValue = null,
						currentValue = null,
						metadata = currentMeta,
					)
				}
			}
		}

		return RepositoryChanges(
			archived = archived,
			unarchived = unarchived,
			deleted = deleted,
			visibilityChanged = visibilityChanged,
			newRepos = newRepos,
		)
	}

	/**
	 * Saves the current repository state to disk.
	 */
	fun saveState(repositories: Map<String, RepositoryMetadata>) {
		try {
			val state = RepositoryState(
				version = 1,
				lastUpdated = System.currentTimeMillis(),
				repositories = repositories,
			)
			stateFile.writeText(json.encodeToString(state))
			logger.debug { "Saved repository state with ${repositories.size} repositories" }
		} catch (e: Exception) {
			logger.warn("Failed to save repository state: ${e.message}")
		}
	}

	private fun loadState(): RepositoryState? {
		if (!stateFile.exists()) {
			logger.debug { "No previous repository state found" }
			return null
		}

		return try {
			val content = stateFile.readText()
			json.decodeFromString<RepositoryState>(content)
		} catch (e: Exception) {
			logger.warn("Failed to load repository state: ${e.message}")
			null
		}
	}
}

@Serializable
internal data class RepositoryState(
	val version: Int,
	val lastUpdated: Long,
	val repositories: Map<String, RepositoryMetadata>,
)

@Serializable
@Poko
internal class RepositoryMetadata(
	val name: String,
	val isArchived: Boolean,
	val isPrivate: Boolean,
	val isFork: Boolean,
	val visibility: String,
	val description: String?,
	val updatedAt: String?,
	val repoType: String, // "owned", "starred", "watching", "gist"
	val diskUsageKb: Long? = null, // Repository size in KB (null for gists)
)

@Poko
internal class RepositoryChange(
	val name: String,
	val changeType: ChangeType,
	val previousValue: String?,
	val currentValue: String?,
	val metadata: RepositoryMetadata,
)

internal enum class ChangeType {
	ARCHIVED,
	UNARCHIVED,
	DELETED,
	VISIBILITY_CHANGED,
	NEW,
}

@Poko
internal class RepositoryChanges(
	val archived: List<RepositoryChange>,
	val unarchived: List<RepositoryChange>,
	val deleted: List<RepositoryChange>,
	val visibilityChanged: List<RepositoryChange>,
	val newRepos: List<RepositoryChange>,
) {
	fun hasChanges(): Boolean =
		archived.isNotEmpty() ||
			unarchived.isNotEmpty() ||
			deleted.isNotEmpty() ||
			visibilityChanged.isNotEmpty()

	fun totalChanges(): Int =
		archived.size + unarchived.size + deleted.size + visibilityChanged.size
}
