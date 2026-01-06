package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test

class TelegramNotificationServiceTest {
	private val quietLogger = Logger(quiet = true, verbosity = 0)

	@Test
	fun `service disabled when config is null`() {
		val service = TelegramNotificationService(config = null, logger = quietLogger)
		assertThat(service.isEnabled()).isFalse()
	}

	@Test
	fun `service disabled when enabled flag is false`() {
		val config = Config.Telegram(
			token = "test-token",
			chatId = "123456",
			enabled = false
		)
		val service = TelegramNotificationService(config = config, logger = quietLogger)
		assertThat(service.isEnabled()).isFalse()
	}

	@Test
	fun `service disabled when chatId is empty`() {
		val config = Config.Telegram(
			token = "test-token",
			chatId = "",
			enabled = true
		)
		val service = TelegramNotificationService(config = config, logger = quietLogger)
		assertThat(service.isEnabled()).isFalse()
	}

	@Test
	fun `service disabled when chatId is blank`() {
		val config = Config.Telegram(
			token = "test-token",
			chatId = "   ",
			enabled = true
		)
		val service = TelegramNotificationService(config = config, logger = quietLogger)
		assertThat(service.isEnabled()).isFalse()
	}

	@Test
	fun `SyncStats has correct default values`() {
		val stats = TelegramNotificationService.SyncStats()

		assertThat(stats.lastSyncStatus).isEqualTo("No sync has been performed yet")
		assertThat(stats.isSyncing).isFalse()
		assertThat(stats.lastSyncTime).isNull()
		assertThat(stats.totalRepositories).isEqualTo(0)
		assertThat(stats.successfulRepositories).isEqualTo(0)
		assertThat(stats.failedRepositories).isEqualTo(0)
		assertThat(stats.lastProgressPercentage).isEqualTo(0)
	}

	@Test
	fun `SyncStats copy updates values atomically`() {
		val initial = TelegramNotificationService.SyncStats()

		val updated = initial.copy(
			isSyncing = true,
			totalRepositories = 100,
			lastSyncStatus = "Syncing..."
		)

		// Initial unchanged
		assertThat(initial.isSyncing).isFalse()
		assertThat(initial.totalRepositories).isEqualTo(0)

		// Updated has new values
		assertThat(updated.isSyncing).isTrue()
		assertThat(updated.totalRepositories).isEqualTo(100)
		assertThat(updated.lastSyncStatus).isEqualTo("Syncing...")
	}

	@Test
	fun `SyncStats completion update preserves all fields`() {
		val syncing = TelegramNotificationService.SyncStats(
			isSyncing = true,
			totalRepositories = 50,
			lastProgressPercentage = 80
		)

		val completed = syncing.copy(
			isSyncing = false,
			successfulRepositories = 48,
			failedRepositories = 2,
			lastSyncTime = "2024-01-15 10:30:00",
			lastSyncStatus = "Completed"
		)

		assertThat(completed.isSyncing).isFalse()
		assertThat(completed.totalRepositories).isEqualTo(50) // Preserved from syncing
		assertThat(completed.successfulRepositories).isEqualTo(48)
		assertThat(completed.failedRepositories).isEqualTo(2)
		assertThat(completed.lastSyncTime).isNotNull()
	}

	@Test
	fun `disabled service does not throw on notification calls`() {
		val service = TelegramNotificationService(config = null, logger = quietLogger)

		// These should not throw even when service is disabled
		service.notifySyncStart(100, 4)
		service.notifyProgress(50, 100, "test-repo")
		service.notifySyncCompletion(95, 5, 3600)
	}

	@Test
	fun `notification service with disabled config handles calls gracefully`() {
		val config = Config.Telegram(
			token = null,
			chatId = "123456",
			enabled = false
		)
		val service = TelegramNotificationService(config = config, logger = quietLogger)

		// Should not throw
		service.notifySyncStart(10, 2)
		service.notifyProgress(5, 10)
		service.notifySyncCompletion(10, 0, 60)

		assertThat(service.isEnabled()).isFalse()
	}
}
