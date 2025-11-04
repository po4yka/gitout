package com.jakewharton.gitout

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Service for sending Telegram notifications about gitout sync activities.
 *
 * This service integrates with the kotlin-telegram-bot library to send real-time
 * notifications about repository synchronization progress, completion, and errors.
 *
 * Example configuration in config.toml:
 * ```toml
 * [telegram]
 * token = "YOUR_BOT_TOKEN"  # Optional - can use TELEGRAM_BOT_TOKEN env var
 * chat_id = "123456789"
 * enabled = true
 * notify_start = true
 * notify_progress = true
 * notify_completion = true
 * notify_errors = true
 * ```
 *
 * @property config Telegram configuration from config.toml
 * @property logger Logger for diagnostic messages
 */
internal class TelegramNotificationService(
	private val config: Config.Telegram?,
	private val logger: Logger,
) {
	// Store the latest sync status for command responses
	private val lastSyncStatus = AtomicReference<String>("No sync has been performed yet")
	private val isSyncing = AtomicReference(false)

	// Store repository statistics
	private val lastSyncTime = AtomicReference<String?>(null)
	private val totalRepositories = AtomicReference(0)
	private val successfulRepositories = AtomicReference(0)
	private val failedRepositories = AtomicReference(0)

	private val bot: Bot? = config?.let { telegramConfig ->
		if (!telegramConfig.enabled) {
			logger.debug { "Telegram notifications disabled by config" }
			return@let null
		}

		val token = resolveTelegramToken(telegramConfig.token)
		if (token == null) {
			logger.warn("Telegram configuration present but no token found. Notifications disabled.")
			return@let null
		}

		try {
			bot {
				this.token = token

				// Set up command handlers if enabled
				if (telegramConfig.enableCommands && telegramConfig.allowedUsers.isNotEmpty()) {
					dispatch {
						command("start") {
							handleCommand(message.from?.id, "start") {
								"👋 Welcome to GitOut Bot!\n\nAvailable commands:\n" +
								"/status - Get current sync status\n" +
								"/stats - Get repository statistics\n" +
								"/info - Get bot and repository information\n" +
								"/help - Show this help message"
							}
						}

						command("help") {
							handleCommand(message.from?.id, "help") {
								"📖 <b>GitOut Bot Help</b>\n\n" +
								"<b>Available Commands:</b>\n" +
								"/status - Get current synchronization status\n" +
								"/stats - Get repository statistics and last sync time\n" +
								"/info - Get bot and repository information\n" +
								"/help - Show this help message\n\n" +
								"<i>This bot monitors GitOut repository synchronization and sends notifications.</i>"
							}
						}

						command("status") {
							handleCommand(message.from?.id, "status") {
								val syncing = isSyncing.get()
								val status = lastSyncStatus.get()

								if (syncing) {
									"🔄 <b>Sync in Progress</b>\n\n$status"
								} else {
									"📊 <b>Last Sync Status</b>\n\n$status"
								}
							}
						}

						command("stats") {
							handleCommand(message.from?.id, "stats") {
								val lastSync = lastSyncTime.get()
								val total = totalRepositories.get()
								val successful = successfulRepositories.get()
								val failed = failedRepositories.get()

								if (lastSync == null) {
									"📊 <b>Repository Statistics</b>\n\n" +
									"No synchronization has been performed yet."
								} else {
									val successRate = if (total > 0) (successful.toDouble() / total * 100).toInt() else 0

									buildString {
										appendLine("📊 <b>Repository Statistics</b>\n")
										appendLine("<b>Last Sync:</b> $lastSync")
										appendLine("<b>Total Repositories:</b> $total")
										appendLine("<b>Successful:</b> $successful ✅")
										if (failed > 0) {
											appendLine("<b>Failed:</b> $failed ❌")
										}
										appendLine("<b>Success Rate:</b> $successRate%")
									}
								}
							}
						}

						command("info") {
							handleCommand(message.from?.id, "info") {
								"ℹ️ <b>GitOut Bot Information</b>\n\n" +
								"<b>Version:</b> $version\n" +
								"<b>Status:</b> ${if (isEnabled()) "✅ Active" else "❌ Inactive"}\n" +
								"<b>Notifications:</b>\n" +
								"  • Start: ${if (telegramConfig.notifyStart) "✅" else "❌"}\n" +
								"  • Progress: ${if (telegramConfig.notifyProgress) "✅" else "❌"}\n" +
								"  • Completion: ${if (telegramConfig.notifyCompletion) "✅" else "❌"}\n" +
								"  • Errors: ${if (telegramConfig.notifyErrors) "✅" else "❌"}\n" +
								"<b>Commands:</b> ${if (telegramConfig.enableCommands) "✅ Enabled" else "❌ Disabled"}\n" +
								"<b>Authorized Users:</b> ${telegramConfig.allowedUsers.size}"
							}
						}
					}
				}
			}.also { createdBot ->
				// Start polling if commands are enabled
				if (telegramConfig.enableCommands && telegramConfig.allowedUsers.isNotEmpty()) {
					logger.info { "Starting Telegram bot polling for commands" }
					createdBot.startPolling()
				}
			}
		} catch (e: Exception) {
			logger.warn("Failed to initialize Telegram bot: ${e.message}")
			null
		}
	}

	private val chatId = config?.chatId?.let { ChatId.fromId(it.toLongOrNull() ?: 0L) }

	/**
	 * Handle a command with user authentication.
	 */
	private fun handleCommand(userId: Long?, commandName: String, responseBuilder: () -> String) {
		if (userId == null) {
			logger.warn("Received /$commandName command from unknown user")
			return
		}

		// Check if user is authorized
		if (config?.allowedUsers?.contains(userId) != true) {
			logger.warn("Unauthorized access attempt to /$commandName from user ID: $userId")
			bot?.sendMessage(
				chatId = ChatId.fromId(userId),
				text = "🚫 <b>Unauthorized</b>\n\nYou are not authorized to use this bot.",
				parseMode = com.github.kotlintelegrambot.entities.ParseMode.HTML
			)
			return
		}

		logger.debug { "Processing /$commandName command from authorized user: $userId" }

		try {
			val response = responseBuilder()
			bot?.sendMessage(
				chatId = ChatId.fromId(userId),
				text = response,
				parseMode = com.github.kotlintelegrambot.entities.ParseMode.HTML
			)
		} catch (e: Exception) {
			logger.warn("Error handling /$commandName command: ${e.message}")
		}
	}

	/**
	 * Resolves the Telegram bot token from multiple sources with the following priority:
	 * 1. config.toml token (if present)
	 * 2. TELEGRAM_BOT_TOKEN_FILE environment variable (path to file containing token)
	 * 3. TELEGRAM_BOT_TOKEN environment variable (token value directly)
	 */
	private fun resolveTelegramToken(configToken: String?): String? {
		// Priority 1: Use token from config.toml if present
		if (configToken != null) {
			logger.debug { "Using Telegram token from config.toml" }
			return configToken
		}

		// Priority 2: Check TELEGRAM_BOT_TOKEN_FILE environment variable
		val tokenFilePath = System.getenv("TELEGRAM_BOT_TOKEN_FILE")
		if (tokenFilePath != null) {
			val tokenFile = Paths.get(tokenFilePath)
			if (tokenFile.exists()) {
				val token = tokenFile.readText().trim()
				if (token.isNotEmpty()) {
					logger.debug { "Using Telegram token from TELEGRAM_BOT_TOKEN_FILE: $tokenFilePath" }
					return token
				} else {
					logger.warn("TELEGRAM_BOT_TOKEN_FILE is empty: $tokenFilePath")
				}
			} else {
				logger.warn("TELEGRAM_BOT_TOKEN_FILE does not exist: $tokenFilePath")
			}
		}

		// Priority 3: Check TELEGRAM_BOT_TOKEN environment variable
		val tokenEnv = System.getenv("TELEGRAM_BOT_TOKEN")
		if (tokenEnv != null && tokenEnv.isNotEmpty()) {
			logger.debug { "Using Telegram token from TELEGRAM_BOT_TOKEN environment variable" }
			return tokenEnv
		}

		// No token found anywhere
		return null
	}

	/**
	 * Checks if the service is enabled and properly configured.
	 */
	internal fun isEnabled(): Boolean = bot != null && chatId != null && config?.enabled == true

	/**
	 * Sends a notification that synchronization is starting.
	 *
	 * @param repositoryCount Total number of repositories to sync
	 * @param workers Number of parallel workers being used
	 */
	internal fun notifySyncStart(repositoryCount: Int, workers: Int) {
		isSyncing.set(true)
		totalRepositories.set(repositoryCount)
		lastSyncStatus.set("Syncing $repositoryCount repositories with $workers workers\nStarted: ${getCurrentTimestamp()}")

		if (!isEnabled() || config?.notifyStart != true) return

		val message = buildString {
			appendLine("🚀 <b>GitOut Sync Started</b>")
			appendLine()
			appendLine("📦 Repositories: $repositoryCount")
			appendLine("⚡ Workers: $workers")
			appendLine("⏰ Started: ${getCurrentTimestamp()}")
		}

		sendMessage(message)
	}

	/**
	 * Sends a progress notification during synchronization.
	 *
	 * @param completed Number of repositories completed
	 * @param total Total number of repositories
	 * @param currentRepo Name of the current repository being synced (optional)
	 */
	internal fun notifyProgress(completed: Int, total: Int, currentRepo: String? = null) {
		if (!isEnabled() || config?.notifyProgress != true) return

		// Only send progress updates at meaningful intervals (every 10% or so)
		val percentage = (completed.toDouble() / total * 100).toInt()
		if (percentage % 10 != 0 && completed != total) return

		val message = buildString {
			appendLine("⏳ <b>Sync Progress</b>")
			appendLine()
			appendLine("✅ Completed: $completed / $total ($percentage%)")
			if (currentRepo != null) {
				appendLine("🔄 Current: <code>$currentRepo</code>")
			}
		}

		sendMessage(message)
	}

	/**
	 * Sends a notification that synchronization completed successfully.
	 *
	 * @param successful Number of successfully synced repositories
	 * @param failed Number of failed repositories
	 * @param durationSeconds Total duration in seconds
	 */
	internal fun notifySyncCompletion(successful: Int, failed: Int, durationSeconds: Long) {
		isSyncing.set(false)

		val total = successful + failed
		val successRate = if (total > 0) (successful.toDouble() / total * 100).toInt() else 0

		// Update statistics
		successfulRepositories.set(successful)
		failedRepositories.set(failed)
		lastSyncTime.set(getCurrentTimestamp())

		lastSyncStatus.set(
			"✅ Successful: $successful\n" +
			if (failed > 0) "❌ Failed: $failed\n" else "" +
			"📊 Success Rate: $successRate%\n" +
			"⏱️ Duration: ${formatDuration(durationSeconds)}\n" +
			"🏁 Finished: ${getCurrentTimestamp()}"
		)

		if (!isEnabled() || config?.notifyCompletion != true) return

		val message = buildString {
			if (failed == 0) {
				appendLine("✅ <b>GitOut Sync Completed Successfully</b>")
			} else {
				appendLine("⚠️ <b>GitOut Sync Completed with Errors</b>")
			}
			appendLine()
			appendLine("✅ Successful: $successful")
			if (failed > 0) {
				appendLine("❌ Failed: $failed")
			}
			appendLine("📊 Success Rate: $successRate%")
			appendLine("⏱️ Duration: ${formatDuration(durationSeconds)}")
			appendLine("🏁 Finished: ${getCurrentTimestamp()}")
		}

		sendMessage(message)
	}

	/**
	 * Sends a notification about synchronization errors.
	 *
	 * @param failedRepos Map of repository names to error messages
	 */
	internal fun notifyErrors(failedRepos: Map<String, String>) {
		if (!isEnabled() || config?.notifyErrors != true || failedRepos.isEmpty()) return

		val message = buildString {
			appendLine("❌ <b>Sync Errors</b>")
			appendLine()
			appendLine("Failed repositories: ${failedRepos.size}")
			appendLine()

			// Limit to first 5 errors to avoid message size limits
			failedRepos.entries.take(5).forEach { (repo, error) ->
				appendLine("• <code>$repo</code>")
				// Truncate error message if too long
				val truncatedError = if (error.length > 100) {
					error.take(97) + "..."
				} else {
					error
				}
				appendLine("  $truncatedError")
				appendLine()
			}

			if (failedRepos.size > 5) {
				appendLine("... and ${failedRepos.size - 5} more errors")
			}
		}

		sendMessage(message)
	}

	/**
	 * Sends a notification about newly discovered repositories.
	 *
	 * @param newRepos List of newly starred/watched repository names
	 */
	internal fun notifyNewRepositories(newRepos: List<String>) {
		if (!isEnabled() || config?.notifyNewRepos != true || newRepos.isEmpty()) return

		val message = buildString {
			appendLine("⭐ <b>New Repositories Discovered</b>")
			appendLine()
			appendLine("Found ${newRepos.size} new ${if (newRepos.size == 1) "repository" else "repositories"} to backup:")
			appendLine()

			// Limit to first 10 repositories to avoid message size limits
			newRepos.take(10).forEach { repo ->
				appendLine("• <code>$repo</code>")
			}

			if (newRepos.size > 10) {
				appendLine()
				appendLine("... and ${newRepos.size - 10} more")
			}

			appendLine()
			appendLine("These will be backed up in the next sync.")
		}

		sendMessage(message)
	}

	/**
	 * Sends a notification about a repository's first backup.
	 *
	 * @param repoName Name of the repository
	 * @param repoUrl URL of the repository
	 */
	internal fun notifyFirstBackup(repoName: String, repoUrl: String) {
		if (!isEnabled() || config?.notifyNewRepos != true) return

		val message = buildString {
			appendLine("💾 <b>First Backup Created</b>")
			appendLine()
			appendLine("<b>Repository:</b> <code>$repoName</code>")
			appendLine("<b>URL:</b> $repoUrl")
			appendLine()
			appendLine("✅ Initial backup completed successfully!")
			appendLine("⏰ ${getCurrentTimestamp()}")
		}

		sendMessage(message)
	}

	/**
	 * Sends a notification about a regular repository update.
	 *
	 * @param repoName Name of the repository
	 * @param repoUrl URL of the repository
	 */
	internal fun notifyRepositoryUpdate(repoName: String, repoUrl: String) {
		if (!isEnabled() || config?.notifyUpdates != true) return

		val message = buildString {
			appendLine("🔄 <b>Repository Updated</b>")
			appendLine()
			appendLine("<b>Repository:</b> <code>$repoName</code>")
			appendLine("<b>URL:</b> $repoUrl")
			appendLine("⏰ ${getCurrentTimestamp()}")
		}

		sendMessage(message)
	}

	/**
	 * Sends a test notification to verify the configuration.
	 */
	internal fun sendTestNotification() {
		if (!isEnabled()) {
			logger.warn("Cannot send test notification: Telegram service is not properly configured")
			return
		}

		val message = buildString {
			appendLine("🧪 <b>GitOut Test Notification</b>")
			appendLine()
			appendLine("✅ Telegram notifications are working correctly!")
			appendLine("⏰ Sent: ${getCurrentTimestamp()}")
		}

		sendMessage(message)
	}

	/**
	 * Sends a message to the configured chat.
	 */
	private fun sendMessage(message: String) {
		if (bot == null || chatId == null) {
			logger.debug { "Skipping Telegram notification: service not initialized" }
			return
		}

		try {
			val result = bot.sendMessage(
				chatId = chatId,
				text = message,
				parseMode = com.github.kotlintelegrambot.entities.ParseMode.HTML
			)

			result.fold(
				ifSuccess = {
					logger.debug { "Telegram notification sent successfully" }
				},
				ifError = { error ->
					logger.warn("Failed to send Telegram notification: $error")
				}
			)
		} catch (e: Exception) {
			logger.warn("Exception while sending Telegram notification: ${e.message}")
		}
	}

	/**
	 * Gets the current timestamp in a readable format.
	 */
	private fun getCurrentTimestamp(): String {
		return java.time.LocalDateTime.now()
			.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
	}

	/**
	 * Formats duration in seconds to a human-readable string.
	 */
	private fun formatDuration(seconds: Long): String {
		val hours = seconds / 3600
		val minutes = (seconds % 3600) / 60
		val secs = seconds % 60

		return when {
			hours > 0 -> "${hours}h ${minutes}m ${secs}s"
			minutes > 0 -> "${minutes}m ${secs}s"
			else -> "${secs}s"
		}
	}
}
