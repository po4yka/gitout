package com.jakewharton.gitout.search

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.jakewharton.gitout.Logger
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class ReadmeExtractorTest {

    private val quietLogger = Logger(quiet = true, level = 0)
    private val extractor = ReadmeExtractor(quietLogger)
    private val tempDirs = mutableListOf<Path>()

    @Before fun setUp() {
        // Ensure git is available
    }

    @After fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun createTempDir(prefix: String): Path {
        val dir = Files.createTempDirectory(prefix)
        tempDirs.add(dir)
        return dir
    }

    private fun runGit(vararg args: String): Int {
        val process = ProcessBuilder(listOf("git") + args.toList())
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes() // drain output
        process.waitFor(30, TimeUnit.SECONDS)
        return process.exitValue()
    }

    private fun runGitIn(dir: Path, vararg args: String): Int {
        val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args.toList())
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes() // drain output
        process.waitFor(30, TimeUnit.SECONDS)
        return process.exitValue()
    }

    private fun createBareRepoWithFile(filename: String, content: String): Path {
        val sourceDir = createTempDir("gitout-readme-source-")
        val bareDir = createTempDir("gitout-readme-bare-")

        runGitIn(sourceDir, "init")
        runGitIn(sourceDir, "config", "user.email", "test@test.com")
        runGitIn(sourceDir, "config", "user.name", "Test")

        sourceDir.resolve(filename).toFile().writeText(content)
        runGitIn(sourceDir, "add", filename)
        runGitIn(sourceDir, "commit", "-m", "init")

        runGit("clone", "--mirror", sourceDir.toString(), bareDir.toString())

        return bareDir
    }

    @Test fun `extract returns README md content from bare repo`() {
        val expectedContent = "# My Project\n\nThis is the README."
        val bareRepo = createBareRepoWithFile("README.md", expectedContent)

        val result = extractor.extract(bareRepo)

        assertThat(result).isEqualTo(expectedContent)
    }

    @Test fun `extract falls back to README rst when README md absent`() {
        val expectedContent = "My Project\n==========\n\nA reStructuredText readme."
        val bareRepo = createBareRepoWithFile("README.rst", expectedContent)

        val result = extractor.extract(bareRepo)

        assertThat(result).isEqualTo(expectedContent)
    }

    @Test fun `extract returns empty string when no README in any format`() {
        val sourceDir = createTempDir("gitout-readme-no-readme-source-")
        val bareDir = createTempDir("gitout-readme-no-readme-bare-")

        runGitIn(sourceDir, "init")
        runGitIn(sourceDir, "config", "user.email", "test@test.com")
        runGitIn(sourceDir, "config", "user.name", "Test")

        sourceDir.resolve("main.kt").toFile().writeText("fun main() {}")
        runGitIn(sourceDir, "add", "main.kt")
        runGitIn(sourceDir, "commit", "-m", "init")

        runGit("clone", "--mirror", sourceDir.toString(), bareDir.toString())

        val result = extractor.extract(bareDir)

        assertThat(result).isEmpty()
    }

    @Test fun `extract truncates content to 8000 characters`() {
        val longContent = "A".repeat(10000)
        val bareRepo = createBareRepoWithFile("README.md", longContent)

        val result = extractor.extract(bareRepo)

        assertThat(result.length).isEqualTo(8000)
        assertThat(result).isEqualTo(longContent.take(8000))
    }
}
