package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class TelegramNotificationTest {
	private val logger = Logger(quiet = false, level = 0)

	@Test
	fun `service is disabled when config is null`() {
		val service = TelegramNotificationService(null, logger)
		assertThat(service.isEnabled()).isFalse()
	}

	@Test
	fun `service is disabled when enabled flag is false`() {
		val config = Config.Telegram(
			token = "test-token",
			chatId = "123456789",
			enabled = false,
		)
		val service = TelegramNotificationService(config, logger)
		assertThat(service.isEnabled()).isFalse()
	}

	@Test
	fun `service is disabled when token is missing and no env var`() {
		val config = Config.Telegram(
			token = null,
			chatId = "123456789",
			enabled = true,
		)
		val service = TelegramNotificationService(config, logger)
		assertThat(service.isEnabled()).isFalse()
	}

	@Test
	fun `notifySyncStart does not throw when service is disabled`() {
		val service = TelegramNotificationService(null, logger)
		// Should not throw
		service.notifySyncStart(100, 4)
	}

	@Test
	fun `notifyProgress does not throw when service is disabled`() {
		val service = TelegramNotificationService(null, logger)
		// Should not throw
		service.notifyProgress(50, 100, "test/repo")
	}

	@Test
	fun `notifySyncCompletion does not throw when service is disabled`() {
		val service = TelegramNotificationService(null, logger)
		// Should not throw
		service.notifySyncCompletion(95, 5, 300)
	}

	@Test
	fun `notifyErrors does not throw when service is disabled`() {
		val service = TelegramNotificationService(null, logger)
		val errors = mapOf("repo1" to "error1", "repo2" to "error2")
		// Should not throw
		service.notifyErrors(errors)
	}

	@Test
	fun `notifyErrors handles empty map gracefully`() {
		val service = TelegramNotificationService(null, logger)
		// Should not throw with empty map
		service.notifyErrors(emptyMap())
	}

	@Test
	fun `sendTestNotification does not throw when service is disabled`() {
		val service = TelegramNotificationService(null, logger)
		// Should not throw
		service.sendTestNotification()
	}
}
