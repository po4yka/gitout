package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.isFailure
import assertk.assertions.isSuccess
import assertk.assertions.messageContains
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class HealthCheckTest {

	private lateinit var tempDir: java.nio.file.Path
	private lateinit var engine: Engine

	@Before fun setUp() {
		tempDir = Files.createTempDirectory("gitout-healthcheck-test-")
		val configFile = tempDir.resolve("config.toml").also {
			it.writeText("version = 0\n")
		}
		engine = Engine(
			config = configFile,
			destination = tempDir,
			timeout = 30.seconds,
			workers = null,
			logger = Logger(quiet = true, level = 0),
			client = OkHttpClient(),
			healthCheck = null,
			telegramService = null,
		)
	}

	@After fun tearDown() {
		tempDir.toFile().deleteRecursively()
	}

	@Test fun preflightCheckPassesOnWritableTempDirectory() {
		val result = runBlocking { engine.preflightStorageCheck(tempDir, timeoutMs = 5_000L) }
		assertThat(result).isSuccess()
	}

	@Test fun preflightCheckFailsWhenTargetDirectoryDoesNotExist() {
		val nonExistent = tempDir.resolve("does-not-exist")
		val result = runBlocking { engine.preflightStorageCheck(nonExistent, timeoutMs = 5_000L) }
		assertThat(result).isFailure().messageContains("does not exist")
	}

	@Test fun preflightCheckFailsWhenTargetIsARegularFile() {
		val regularFile = tempDir.resolve("file.txt").also { it.writeText("hello") }
		val result = runBlocking { engine.preflightStorageCheck(regularFile, timeoutMs = 5_000L) }
		assertThat(result).isFailure().messageContains("not a directory")
	}

	@Test fun preflightCheckWithZeroTimeoutFailsFast() {
		// A timeout of 0ms causes the withTimeout block to time out immediately,
		// returning a failure rather than completing the write/read cycle.
		val result = runBlocking { engine.preflightStorageCheck(tempDir, timeoutMs = 0L) }
		assertThat(result).isFailure()
	}
}
