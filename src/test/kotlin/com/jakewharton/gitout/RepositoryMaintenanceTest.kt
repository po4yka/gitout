package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

class RepositoryMaintenanceTest {
	private fun createLogger() = Logger(quiet = true, level = 0)

	@Test fun disabledMaintenanceDoesNothing() {
		val config = Config.Maintenance(enabled = false)
		val maintenance = RepositoryMaintenance(createLogger(), config, 10.minutes)
		val tempDir = Files.createTempDirectory("gitout-test-")
		try {
			// Should not throw or fail
			maintenance.runPostSyncMaintenance(tempDir)
		} finally {
			tempDir.toFile().deleteRecursively()
		}
	}

	@Test fun shouldRunFullRepackReturnsFalseWhenDisabled() {
		val config = Config.Maintenance(enabled = false)
		val maintenance = RepositoryMaintenance(createLogger(), config, 10.minutes)
		assertThat(maintenance.shouldRunFullRepack()).isFalse()
	}

	@Test fun shouldRunFullRepackReturnsFalseWhenNever() {
		val config = Config.Maintenance(enabled = true, fullRepackInterval = "never")
		val maintenance = RepositoryMaintenance(createLogger(), config, 10.minutes)
		// Run multiple sync cycles
		repeat(100) {
			assertThat(maintenance.shouldRunFullRepack()).isFalse()
		}
	}

	@Test fun shouldRunFullRepackWeeklyTriggersEvery7Syncs() {
		val config = Config.Maintenance(enabled = true, fullRepackInterval = "weekly")
		val maintenance = RepositoryMaintenance(createLogger(), config, 10.minutes)

		// First 6 syncs should not trigger
		repeat(6) {
			assertThat(maintenance.shouldRunFullRepack()).isFalse()
		}
		// 7th sync should trigger
		assertThat(maintenance.shouldRunFullRepack()).isTrue()

		// Next 6 should not
		repeat(6) {
			assertThat(maintenance.shouldRunFullRepack()).isFalse()
		}
		// 14th sync should trigger
		assertThat(maintenance.shouldRunFullRepack()).isTrue()
	}

	@Test fun shouldRunFullRepackMonthlyTriggersEvery30Syncs() {
		val config = Config.Maintenance(enabled = true, fullRepackInterval = "monthly")
		val maintenance = RepositoryMaintenance(createLogger(), config, 10.minutes)

		// First 29 syncs should not trigger
		repeat(29) {
			assertThat(maintenance.shouldRunFullRepack()).isFalse()
		}
		// 30th sync should trigger
		assertThat(maintenance.shouldRunFullRepack()).isTrue()
	}

	@Test fun maintenanceOnNonExistentPathDoesNothing() {
		val config = Config.Maintenance(enabled = true, strategy = "gc-auto")
		val maintenance = RepositoryMaintenance(createLogger(), config, 10.minutes)
		val nonExistentPath = Files.createTempDirectory("gitout-test-").resolve("nonexistent")
		// Should not throw
		maintenance.runPostSyncMaintenance(nonExistentPath)
	}

	@Test fun fullRepackOnNonExistentPathDoesNothing() {
		val config = Config.Maintenance(enabled = true)
		val maintenance = RepositoryMaintenance(createLogger(), config, 10.minutes)
		val nonExistentPath = Files.createTempDirectory("gitout-test-").resolve("nonexistent")
		// Should not throw
		maintenance.runFullRepack(nonExistentPath)
	}

	@Test fun unknownStrategyLogsWarning() {
		val config = Config.Maintenance(enabled = true, strategy = "unknown")
		val maintenance = RepositoryMaintenance(createLogger(), config, 10.minutes)
		val tempDir = Files.createTempDirectory("gitout-test-")
		try {
			// Should not throw
			maintenance.runPostSyncMaintenance(tempDir)
		} finally {
			tempDir.toFile().deleteRecursively()
		}
	}
}
