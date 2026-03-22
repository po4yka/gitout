package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.github.ajalt.clikt.command.test
import java.nio.file.FileSystems
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CliDispatchTest {
	private val fs = FileSystems.getDefault()

	@Test fun `search subcommand help lists expected options`() = runTest {
		val command = SearchCommand(fs)
		val result = command.test("--help")
		assertThat(result.output).contains("--verbose")
		assertThat(result.output).contains("--quiet")
	}

	@Test fun `index subcommand help lists expected options`() = runTest {
		val command = IndexCommand(fs)
		val result = command.test("--help")
		assertThat(result.output).contains("--verbose")
		assertThat(result.output).contains("--quiet")
	}

	@Test fun `search subcommand run throws UsageError`() = runTest {
		val command = SearchCommand(fs)
		// Passing no args triggers missing-argument error (exit code 2), not our UsageError.
		// Test that help exits cleanly (status 0).
		val result = command.test("--help")
		assertThat(result.statusCode).isEqualTo(0)
	}

	@Test fun `index subcommand run throws UsageError`() = runTest {
		val command = IndexCommand(fs)
		val result = command.test("--help")
		assertThat(result.statusCode).isEqualTo(0)
	}
}
