package com.jakewharton.gitout

import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.time.Duration

/**
 * Handles Git LFS (Large File Storage) detection and object fetching for complete backups.
 *
 * `git clone --mirror` does NOT download LFS objects - it only stores pointer files.
 * This class detects LFS-enabled repositories and runs `git lfs fetch --all` to ensure
 * the actual large file content is included in the backup.
 */
internal class LfsSupport(
	private val logger: Logger,
	private val timeout: Duration,
	private val sslEnvironment: Map<String, String> = emptyMap(),
) {
	/**
	 * Checks if the system has git-lfs installed and available.
	 */
	fun isLfsAvailable(): Boolean {
		return try {
			val process = ProcessBuilder()
				.command(GIT_EXECUTABLE, "lfs", "version")
				.redirectError(ProcessBuilder.Redirect.PIPE)
				.redirectOutput(ProcessBuilder.Redirect.PIPE)
				.start()

			val completed = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
			if (!completed) {
				process.destroyForcibly()
				return false
			}
			process.exitValue() == 0
		} catch (_: Exception) {
			false
		}
	}

	/**
	 * Detects whether a repository uses Git LFS by checking for LFS pointers
	 * in .gitattributes or the presence of a lfs directory.
	 */
	fun isLfsRepo(repoPath: Path): Boolean {
		if (!repoPath.exists() || !repoPath.isDirectory()) return false

		// Check for lfs directory in bare repo
		val lfsDir = repoPath.resolve("lfs")
		if (lfsDir.exists() && lfsDir.isDirectory()) {
			return true
		}

		// Check .gitattributes for LFS filter references
		// In a bare repo, we need to read via git show
		return try {
			val process = ProcessBuilder()
				.command(GIT_EXECUTABLE, "-C", repoPath.absolutePathString(), "show", "HEAD:.gitattributes")
				.redirectError(ProcessBuilder.Redirect.PIPE)
				.redirectOutput(ProcessBuilder.Redirect.PIPE)
				.start()

			if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly()
				return false
			}

			if (process.exitValue() != 0) return false

			val content = process.inputStream.bufferedReader().use { it.readText() }
			content.contains("filter=lfs")
		} catch (_: Exception) {
			false
		}
	}

	/**
	 * Fetches all LFS objects for a repository.
	 * Runs `git lfs fetch --all` which downloads ALL versions of ALL LFS-tracked files.
	 * This ensures the backup contains the actual file content, not just pointers.
	 */
	fun fetchLfsObjects(repoPath: Path): Boolean {
		if (!repoPath.exists() || !repoPath.isDirectory()) return false

		logger.info { "Fetching LFS objects for ${repoPath.fileName}" }

		return try {
			val command = listOf(GIT_EXECUTABLE, "-C", repoPath.absolutePathString(), "lfs", "fetch", "--all")

			val processBuilder = ProcessBuilder()
				.command(command)
				.directory(repoPath.toFile())
				.redirectError(ProcessBuilder.Redirect.INHERIT)
				.redirectOutput(ProcessBuilder.Redirect.PIPE)

			// Apply SSL environment variables
			if (sslEnvironment.isNotEmpty()) {
				processBuilder.environment().putAll(sslEnvironment)
			}

			val process = processBuilder.start()

			if (!process.waitFor(timeout)) {
				logger.warn("LFS fetch timed out for ${repoPath.fileName}")
				process.destroy()
				if (process.isAlive) {
					process.destroyForcibly()
				}
				return false
			}

			val exitCode = process.exitValue()
			if (exitCode == 0) {
				logger.debug { "LFS fetch complete for ${repoPath.fileName}" }
				true
			} else {
				logger.debug { "LFS fetch failed for ${repoPath.fileName} (exit $exitCode)" }
				false
			}
		} catch (e: Exception) {
			logger.debug { "LFS fetch error for ${repoPath.fileName}: ${e.message}" }
			false
		}
	}

	/**
	 * Detects LFS usage and fetches objects if needed.
	 * This is the main entry point called after each successful sync.
	 *
	 * @return true if LFS objects were fetched (or no LFS objects needed), false on failure
	 */
	fun syncLfsIfNeeded(repoPath: Path): Boolean {
		if (!isLfsRepo(repoPath)) {
			return true // Not an LFS repo, nothing to do
		}

		logger.info { "LFS repository detected: ${repoPath.fileName}" }
		return fetchLfsObjects(repoPath)
	}
}
