package com.jakewharton.gitout

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.time.Duration

/**
 * Resolves the absolute path to the `git` executable by searching PATH.
 * Falls back to "git" if not found (will fail later with a clear error).
 */
internal val GIT_EXECUTABLE: String by lazy {
	val pathDirs = System.getenv("PATH")?.split(java.io.File.pathSeparator).orEmpty()
	for (dir in pathDirs) {
		val candidate: Path = Paths.get(dir, "git")
		if (candidate.exists() && candidate.isExecutable()) {
			return@lazy candidate.toAbsolutePath().toString()
		}
	}
	"git" // fallback
}

internal fun Process.waitFor(timeout: Duration): Boolean {
	return timeout.toComponents { seconds, nanoseconds ->
		var seconds = seconds
		if (nanoseconds != 0 && seconds < Long.MAX_VALUE) {
			seconds += 1
		}
		waitFor(seconds, SECONDS)
	}
}
