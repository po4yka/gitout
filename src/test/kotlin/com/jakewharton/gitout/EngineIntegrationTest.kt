package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.isTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

class EngineIntegrationTest {
    @Test fun clonesPublicRepository() {
        val tempDir = Files.createTempDirectory("gitout-engine-test-")
        try {
            val configFile = tempDir.resolve("config.toml")
            configFile.toFile().writeText(
                """
                version = 0

                [git.repos]
                gitout = "https://github.com/JakeWharton/gitout.git"
                """.trimIndent()
            )

            val destination = tempDir.resolve("dest").apply { createDirectories() }

            val engine = Engine(
                config = configFile,
                destination = destination,
                timeout = 60.seconds,
                workers = null,
                logger = Logger(quiet = true, level = 0),
                client = OkHttpClient(),
                healthCheck = null,
                telegramService = null,
            )

            runBlocking {
                engine.performSync(dryRun = false)
            }

            val repoDir = destination.resolve("git").resolve("gitout")
            assertThat(repoDir.exists()).isTrue()
            assertThat(repoDir.resolve("config").exists()).isTrue()
            assertThat(repoDir.resolve("HEAD").exists()).isTrue()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
