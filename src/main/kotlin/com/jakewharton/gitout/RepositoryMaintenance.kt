package com.jakewharton.gitout

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.streams.toList
import kotlin.time.Duration

/**
 * Handles post-sync repository maintenance operations to keep backup repositories compact.
 *
 * Supports three maintenance strategies:
 * - `gc-auto`: Runs `git gc --auto` after each sync (only acts when thresholds are met)
 * - `geometric`: Runs `git repack --geometric=2 -d` for incremental optimization
 * - `none`: No automatic maintenance
 *
 * Additionally supports:
 * - Periodic full repack (`git repack -a -d`) on a configurable schedule
 * - Commit-graph generation for faster read operations
 */
internal class RepositoryMaintenance(
	private val logger: Logger,
	private val config: Config.Maintenance,
	private val timeout: Duration,
) {
	private var syncCount: Int = 0

	/**
	 * Runs post-sync maintenance on a single repository after a successful sync.
	 * The strategy is determined by [Config.Maintenance.strategy].
	 */
	fun runPostSyncMaintenance(repoPath: Path) {
		if (!config.enabled) return
		if (!repoPath.exists() || !repoPath.isDirectory()) return

		when (config.strategy) {
			"gc-auto" -> runGcAuto(repoPath)
			"geometric" -> runGeometricRepack(repoPath)
			"none" -> logger.debug { "Maintenance disabled for ${repoPath.fileName}" }
			else -> logger.warn("Unknown maintenance strategy: ${config.strategy}, skipping")
		}

		if (config.writeCommitGraph) {
			writeCommitGraph(repoPath)
		}
	}

	/**
	 * Runs `git gc --auto` on a repository.
	 * This only performs garbage collection when internal thresholds are met
	 * (>6700 loose objects or >50 packs), making it very cheap when not needed.
	 */
	private fun runGcAuto(repoPath: Path) {
		logger.debug { "Running git gc --auto on ${repoPath.fileName}" }
		executeGitCommand(
			repoPath,
			listOf("git", "-C", repoPath.absolutePathString(), "gc", "--auto"),
			"gc --auto",
		)
	}

	/**
	 * Runs `git repack --geometric=2 -d` on a repository.
	 * This merges only enough small packs to maintain a geometric progression of pack sizes,
	 * making each repack proportional to new data rather than total repository size.
	 */
	private fun runGeometricRepack(repoPath: Path) {
		logger.debug { "Running geometric repack on ${repoPath.fileName}" }
		executeGitCommand(
			repoPath,
			listOf("git", "-C", repoPath.absolutePathString(), "repack", "--geometric=2", "-d"),
			"geometric repack",
		)
	}

	/**
	 * Writes a commit-graph file for faster traversal operations.
	 * Costs <1% storage overhead but provides ~10x faster commit graph traversal.
	 */
	private fun writeCommitGraph(repoPath: Path) {
		logger.debug { "Writing commit-graph for ${repoPath.fileName}" }
		executeGitCommand(
			repoPath,
			listOf("git", "-C", repoPath.absolutePathString(), "commit-graph", "write", "--reachable"),
			"commit-graph write",
		)
	}

	/**
	 * Increments the sync cycle counter and checks whether a full repack should be triggered.
	 * Returns true if a full repack is due based on [Config.Maintenance.fullRepackInterval].
	 */
	fun shouldRunFullRepack(): Boolean {
		if (!config.enabled) return false
		syncCount++
		return when (config.fullRepackInterval) {
			"never" -> false
			// "weekly" assumes ~1 sync/day = 7 syncs, "monthly" = 30 syncs
			"weekly" -> syncCount % 7 == 0
			"monthly" -> syncCount % 30 == 0
			else -> false
		}
	}

	/**
	 * Runs a full repack on all git repositories under the given destination directory.
	 * Uses `git repack -a -d` with configurable window and depth for optimal compression.
	 * This is more expensive than incremental maintenance but produces better compression.
	 */
	fun runFullRepack(destinationPath: Path) {
		if (!config.enabled) return
		if (!destinationPath.exists() || !destinationPath.isDirectory()) return

		logger.info { "Running periodic full repack (window=${config.repackWindow}, depth=${config.repackDepth})" }

		val repos = findGitRepos(destinationPath)
		if (repos.isEmpty()) {
			logger.debug { "No git repositories found under $destinationPath" }
			return
		}

		logger.info { "Full repack: processing ${repos.size} repositories" }
		for (repo in repos) {
			logger.debug { "Full repack: ${repo.fileName}" }
			executeGitCommand(
				repo,
				listOf(
					"git", "-C", repo.absolutePathString(),
					"repack", "-a", "-d",
					"--window=${config.repackWindow}",
					"--depth=${config.repackDepth}",
				),
				"full repack",
			)
		}
		logger.info { "Full repack complete" }
	}

	/**
	 * Finds all bare git repositories under the given path (directories containing a HEAD file).
	 */
	private fun findGitRepos(root: Path): List<Path> {
		if (!root.exists()) return emptyList()
		return Files.walk(root, 4).use { stream ->
			stream
				.filter { it.isDirectory() && it.resolve("HEAD").exists() }
				.toList()
		}
	}

	/**
	 * Executes a git command with timeout handling and error logging.
	 */
	private fun executeGitCommand(repoPath: Path, command: List<String>, description: String) {
		try {
			val process = ProcessBuilder()
				.command(command)
				.directory(repoPath.toFile())
				.redirectError(ProcessBuilder.Redirect.PIPE)
				.redirectOutput(ProcessBuilder.Redirect.PIPE)
				.start()

			if (!process.waitFor(timeout)) {
				logger.warn("$description timed out for ${repoPath.fileName}")
				process.destroy()
				if (process.isAlive) {
					process.destroyForcibly()
				}
				return
			}

			val exitCode = process.exitValue()
			if (exitCode != 0) {
				val stderr = process.errorStream.bufferedReader().use { it.readText().trim() }
				logger.debug { "$description failed for ${repoPath.fileName} (exit $exitCode): $stderr" }
			}
		} catch (e: Exception) {
			logger.debug { "$description error for ${repoPath.fileName}: ${e.message}" }
		}
	}
}
