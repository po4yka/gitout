package com.jakewharton.gitout.search

import com.jakewharton.gitout.Logger
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class ReadmeExtractor(private val logger: Logger) {

    internal fun extract(bareRepoPath: Path): String {
        val candidates = listOf("README.md", "readme.md", "README.rst", "README.txt", "README")
        val absolutePath = bareRepoPath.toAbsolutePath().toString()

        for (filename in candidates) {
            val command = listOf("git", "--git-dir=$absolutePath", "show", "HEAD:$filename")
            try {
                val processBuilder = ProcessBuilder(command)
                processBuilder.redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                val process = processBuilder.start()

                val output = process.inputStream.readBytes().toString(Charsets.UTF_8)

                val finished = process.waitFor(30, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    continue
                }

                if (process.exitValue() == 0) {
                    val truncated = output.take(8000)
                    logger.debug { "Extracted README from $bareRepoPath: ${truncated.length} chars" }
                    return truncated
                }
            } catch (e: Exception) {
                logger.trace { "Failed to run git show for $filename in $bareRepoPath: ${e.message}" }
            }
        }

        logger.trace { "No README found in $bareRepoPath" }
        return ""
    }
}
