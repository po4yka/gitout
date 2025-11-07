package com.jakewharton.gitout

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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
	private val lastFailedRepositories = AtomicReference<Map<String, String>>(emptyMap())
	private val botStartedAt = Instant.now()

	// Store repository statistics
        private val lastSyncTime = AtomicReference<String?>(null)
        private val totalRepositories = AtomicReference(0)
        private val successfulRepositories = AtomicReference(0)
        private val failedRepositories = AtomicReference(0)
        private val lastProgressPercentage = AtomicInteger(0)

        private val progressStepPercent = config
                ?.notifyProgressStepPercent
                ?.coerceAtLeast(1)
                ?: DEFAULT_TELEGRAM_PROGRESS_STEP_PERCENT

        private val errorKeywordMatchers: Map<ErrorCategory, List<String>> = buildErrorKeywordMatchers()

        private val chatId = config
                ?.chatId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::resolveChatId)

	private val bot: Bot? = when (config) {
		null -> {
			logger.lifecycle { "Telegram notifications disabled: no [telegram] configuration provided" }
			null
		}
		else -> initializeBot(config)
	}

	private fun initializeBot(telegramConfig: Config.Telegram): Bot? {
		if (!telegramConfig.enabled) {
			logger.lifecycle { "Telegram notifications disabled via config flag" }
			return null
		}

		if (chatId == null) {
			logger.warn("Telegram configuration present but chat_id is missing or invalid. Notifications disabled.")
			return null
		}

		val token = resolveTelegramToken(telegramConfig.token)
		if (token == null) {
			logger.warn("Telegram configuration present but no token found. Notifications disabled.")
			return null
		}

		val commandsEnabled = telegramConfig.enableCommands && telegramConfig.allowedUsers.isNotEmpty()
		if (telegramConfig.enableCommands && telegramConfig.allowedUsers.isEmpty()) {
			logger.warn("Telegram commands requested but allowed_users is empty. Commands will remain disabled.")
		}

		return try {
			bot {
				this.token = token

				if (commandsEnabled) {
					dispatch {
						command("ping") {
							handleCommand(message.from?.id, "ping") {
								val uptime = Duration.between(botStartedAt, Instant.now()).toHumanReadable()
								"<b>Pong!</b>\n\n" +
									"<b>Status:</b> ${if (isEnabled()) "Active" else "Inactive"}\n" +
									"<b>Uptime:</b> $uptime"
							}
						}

						command("start") {
							handleCommand(message.from?.id, "start") {
								"Welcome to GitOut Bot!\n\nAvailable commands:\n" +
									"/status - Get current sync status\n" +
									"/stats - Get repository statistics\n" +
									"/info - Get bot and repository information\n" +
									"/help - Show this help message"
							}
						}

						command("help") {
							handleCommand(message.from?.id, "help") {
								"<b>GitOut Bot Help</b>\n\n" +
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
									"<b>Sync in Progress</b>\n\n$status"
								} else {
									"<b>Last Sync Status</b>\n\n$status"
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
									"<b>Repository Statistics</b>\n\nNo synchronization has been performed yet."
								} else {
									val successRate = if (total > 0) (successful.toDouble() / total * 100).toInt() else 0

									buildString {
										appendLine("<b>Repository Statistics</b>\n")
										appendLine("<b>Last Sync:</b> $lastSync")
										appendLine("<b>Total Repositories:</b> $total")
										appendLine("<b>Successful:</b> $successful")
										if (failed > 0) {
											appendLine("<b>Failed:</b> $failed")
										}
										appendLine("<b>Success Rate:</b> $successRate%")
									}
								}
							}
						}

						command("fails") {
							handleCommand(message.from?.id, "fails") {
								val failures = lastFailedRepositories.get()
								if (failures.isEmpty()) {
									"<b>No recent repository failures.</b>"
								} else {
									buildString {
										appendLine("<b>Recent Repository Failures</b>")
										appendLine()
										failures.entries.take(10).forEach { (repo, error) ->
											appendLine("- <code>$repo</code>")
											appendLine("  ${error.trim()}")
											appendLine()
										}
										if (failures.size > 10) {
											appendLine("...and ${failures.size - 10} more")
										}
									}
								}
							}
						}

						command("info") {
							handleCommand(message.from?.id, "info") {
								"<b>GitOut Bot Information</b>\n\n" +
									"<b>Version:</b> $version\n" +
									"<b>Status:</b> ${if (isEnabled()) "Active" else "Inactive"}\n" +
									"<b>Notifications:</b>\n" +
									"  - Start: ${if (telegramConfig.notifyStart) "Enabled" else "Disabled"}\n" +
									"  - Progress: ${if (telegramConfig.notifyProgress) "Enabled" else "Disabled"}\n" +
									"  - Completion: ${if (telegramConfig.notifyCompletion) "Enabled" else "Disabled"}\n" +
									"  - Errors: ${if (telegramConfig.notifyErrors) "Enabled" else "Disabled"}\n" +
									"<b>Commands:</b> ${if (telegramConfig.enableCommands) "Enabled" else "Disabled"}\n" +
									"<b>Authorized Users:</b> ${telegramConfig.allowedUsers.size}"
							}
						}
					}
				}
			}.also { createdBot ->
				logger.lifecycle {
					val commandsState = if (commandsEnabled) {
						"commands enabled for ${telegramConfig.allowedUsers.size} authorized user(s)"
					} else {
						"command interface disabled"
					}
					"Telegram bot initialized for chat ${telegramConfig.chatId} ($commandsState)"
				}

				if (commandsEnabled) {
					logger.lifecycle { "Starting Telegram bot polling for commands" }
					createdBot.startPolling()
				}
			}
		} catch (e: Exception) {
			logger.warn("Failed to initialize Telegram bot: ${e.message}")
			null
		}
	}

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
				text = "<b>Unauthorized</b>\n\nYou are not authorized to use this bot.",
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
		val tokenFromConfig = configToken?.trim()
		if (!tokenFromConfig.isNullOrEmpty()) {
			logger.debug { "Using Telegram token from config.toml" }
			return tokenFromConfig
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
                lastProgressPercentage.set(0)

                if (!isEnabled() || config?.notifyStart != true) return

                val message = buildString {
			appendLine("<b>GitOut Sync Started</b>")
			appendLine()
			appendLine("Repositories: $repositoryCount")
			appendLine("Workers: $workers")
			appendLine("Started: ${getCurrentTimestamp()}")
		}

		sendMessage(message, "sync start")
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

                if (total <= 0) {
                        logger.debug { "Skipping progress notification: total repository count is $total" }
                        return
                }

                val percentage = (completed.toDouble() / total * 100).toInt().coerceIn(0, 100)
                val lastNotified = lastProgressPercentage.get()
                val shouldSend = when {
                        completed >= total -> lastNotified < 100
                        percentage >= lastNotified + progressStepPercent -> true
                        percentage >= 50 && lastNotified < 50 -> true
                        else -> false
                }

                if (!shouldSend) return

                lastProgressPercentage.set(if (completed >= total) 100 else percentage)

                val message = buildString {
                        appendLine("<b>Sync Progress</b>")
			appendLine()
			appendLine("Completed: $completed / $total ($percentage%)")
			if (currentRepo != null) {
				appendLine("Current: <code>$currentRepo</code>")
			}
		}

		sendMessage(message, "progress")
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
			"Successful: $successful\n" +
				(if (failed > 0) "Failed: $failed\n" else "") +
				"Success Rate: $successRate%\n" +
				"Duration: ${formatDuration(durationSeconds)}\n" +
				"Finished: ${getCurrentTimestamp()}"
		)

		if (!isEnabled() || config?.notifyCompletion != true) return

		val message = buildString {
			if (failed == 0) {
				appendLine("<b>GitOut Sync Completed Successfully</b>")
			} else {
				appendLine("<b>GitOut Sync Completed with Errors</b>")
			}
			appendLine()
			appendLine("Successful: $successful")
			if (failed > 0) {
				appendLine("Failed: $failed")
			}
			appendLine("Success Rate: $successRate%")
			appendLine("Duration: ${formatDuration(durationSeconds)}")
			appendLine("Finished: ${getCurrentTimestamp()}")
		}

		sendMessage(message, "sync completion")
	}

	/**
	 * Sends a notification about synchronization errors.
	 *
	 * @param failedRepos Map of repository names to error messages
	 */
	internal fun notifyErrors(failedRepos: Map<String, String>) {
		if (!isEnabled() || config?.notifyErrors != true || failedRepos.isEmpty()) return

		lastFailedRepositories.set(failedRepos.toMap())

		val message = buildString {
			appendLine("<b>Sync Errors</b>")
			appendLine()
			appendLine("Failed repositories: ${failedRepos.size}")
			appendLine()

			// Limit to first 5 errors to avoid message size limits
			failedRepos.entries.take(5).forEach { (repo, error) ->
				appendLine("- <code>$repo</code>")
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

		sendMessage(message, "error summary")
	}

	/**
	 * Sends an immediate notification about a single repository sync failure.
	 *
	 * @param repoName Name of the repository that failed
	 * @param repoUrl URL of the repository
	 * @param errorMessage The error message describing what went wrong
	 */
	internal fun notifySyncError(repoName: String, repoUrl: String, errorMessage: String) {
		if (!isEnabled() || config?.notifyErrors != true) return
		if (!shouldNotifyForRepository(repoName)) return

		// Categorize the error and get suggestions
		val errorCategory = categorizeError(errorMessage)
		val suggestion = getSuggestion(errorCategory)

		// Truncate error message if too long
		val truncatedError = if (errorMessage.length > 200) {
			errorMessage.take(197) + "..."
		} else {
			errorMessage
		}

		// Map error category to label
		val categoryLabel = when (errorCategory) {
			ErrorCategory.NETWORK -> "Network Error"
			ErrorCategory.AUTHENTICATION -> "Authentication Error"
			ErrorCategory.GIT_ERROR -> "Git Error"
			ErrorCategory.DISK_SPACE -> "Disk Space Error"
			ErrorCategory.RATE_LIMITING -> "Rate Limiting"
			ErrorCategory.SSL_TLS -> "SSL/TLS Error"
			ErrorCategory.UNKNOWN -> "Unknown Error"
		}

		val message = buildString {
			appendLine("<b>Repository Sync Failed</b>")
			appendLine()
			appendLine("<b>Repository:</b> <code>$repoName</code>")
			appendLine("<b>URL:</b> $repoUrl")
			appendLine()
			appendLine("<b>Error Type:</b> $categoryLabel")
			appendLine()
			appendLine("<b>Error:</b>")
			appendLine("<code>$truncatedError</code>")
			appendLine()
			appendLine("<b>Suggestion:</b>")
			appendLine("<i>$suggestion</i>")
			appendLine()
			appendLine("Timestamp: ${getCurrentTimestamp()}")
		}

		sendMessage(message, "repo failure")

		lastFailedRepositories.getAndUpdate { current ->
			current + (repoName to truncatedError)
		}
	}

	/**
	 * Sends a notification about newly discovered repositories.
	 *
	 * @param newRepos List of newly starred/watched repository names
	 */
	internal fun notifyNewRepositories(newRepos: List<String>) {
		if (!isEnabled() || config?.notifyNewRepos != true || newRepos.isEmpty()) return

		// Filter repositories based on notification rules
		val filteredRepos = newRepos.filter { shouldNotifyForRepository(it) }
		if (filteredRepos.isEmpty()) return

		val message = buildString {
			appendLine("<b>New Repositories Discovered</b>")
			appendLine()
			appendLine("Found ${filteredRepos.size} new ${if (filteredRepos.size == 1) "repository" else "repositories"} to backup:")
			appendLine()

			// Limit to first 10 repositories to avoid message size limits
			filteredRepos.take(10).forEach { repo ->
				appendLine("- <code>$repo</code>")
			}

			if (filteredRepos.size > 10) {
				appendLine()
				appendLine("... and ${filteredRepos.size - 10} more")
			}

			appendLine()
			appendLine("These will be backed up in the next sync.")
		}

		sendMessage(message, "new repositories")
	}

	/**
	 * Sends a notification about a repository's first backup.
	 *
	 * @param repoName Name of the repository
	 * @param repoUrl URL of the repository
	 */
	internal fun notifyFirstBackup(repoName: String, repoUrl: String) {
		if (!isEnabled() || config?.notifyNewRepos != true) return
		if (!shouldNotifyForRepository(repoName)) return

		val message = buildString {
			appendLine("<b>First Backup Created</b>")
			appendLine()
			appendLine("<b>Repository:</b> <code>$repoName</code>")
			appendLine("<b>URL:</b> $repoUrl")
			appendLine()
			appendLine("Initial backup completed successfully!")
			appendLine("Timestamp: ${getCurrentTimestamp()}")
		}

		sendMessage(message, "first backup")
	}

	/**
	 * Sends a notification about a regular repository update.
	 *
	 * @param repoName Name of the repository
	 * @param repoUrl URL of the repository
	 * @param commitCount Number of new commits (0 if unknown)
	 */
	internal fun notifyRepositoryUpdate(repoName: String, repoUrl: String, commitCount: Int = 0) {
		if (!isEnabled() || config?.notifyUpdates != true) return
		if (!shouldNotifyForRepository(repoName)) return

		val message = buildString {
			appendLine("<b>Repository Updated</b>")
			appendLine()
			appendLine("<b>Repository:</b> <code>$repoName</code>")
			appendLine("<b>URL:</b> $repoUrl")
			if (commitCount > 0) {
				val commitWord = if (commitCount == 1) "commit" else "commits"
				appendLine("<b>Changes:</b> $commitCount new $commitWord")
			}
			appendLine("Timestamp: ${getCurrentTimestamp()}")
		}

		sendMessage(message, "repository update")
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
			appendLine("<b>GitOut Test Notification</b>")
			appendLine()
			appendLine("Telegram notifications are working correctly!")
			appendLine("Sent: ${getCurrentTimestamp()}")
		}

		sendMessage(message, "test")
	}

	/**
	 * Checks if notifications should be sent for a specific repository.
	 * Takes into account the whitelist (notify_only_repos) and blacklist (notify_ignore_repos).
	 */
	private fun shouldNotifyForRepository(repoName: String): Boolean {
		val onlyRepos = config?.notifyOnlyRepos ?: emptyList()
		val ignoreRepos = config?.notifyIgnoreRepos ?: emptyList()

		// Check blacklist first
		if (ignoreRepos.any { matchesPattern(repoName, it) }) {
			logger.debug { "Repository $repoName is in ignore list" }
			return false
		}

		// If whitelist is empty, allow all (except blacklisted)
		if (onlyRepos.isEmpty()) {
			return true
		}

		// Check whitelist
		val allowed = onlyRepos.any { matchesPattern(repoName, it) }
		if (!allowed) {
			logger.debug { "Repository $repoName is not in notify_only list" }
		}
		return allowed
	}

	/**
	 * Matches a repository name against a pattern (supports wildcards).
	 */
        private fun matchesPattern(repoName: String, pattern: String): Boolean {
                val escaped = Regex.escape(pattern)
                        .replace("\\*", ".*")
                        .replace("\\?", ".")

                return try {
                        Regex("^$escaped$").matches(repoName)
                } catch (e: Exception) {
                        logger.warn("Invalid pattern: $pattern")
                        false
                }
        }

	/**
	 * Categories of errors that can occur during repository synchronization.
	 */
	private enum class ErrorCategory {
		NETWORK,
		AUTHENTICATION,
		GIT_ERROR,
		DISK_SPACE,
		RATE_LIMITING,
		SSL_TLS,
		UNKNOWN
	}

	/**
	 * Categorizes an error based on its message content.
	 */
        private fun categorizeError(errorMessage: String): ErrorCategory {
                val lowerMessage = errorMessage.lowercase()

                errorKeywordMatchers.forEach { (category, keywords) ->
                        if (keywords.any { lowerMessage.contains(it) }) {
                                return category
                        }
                }

                logger.debug { "Uncategorized error message for Telegram notifications: ${errorMessage.trim()}" }
                return ErrorCategory.UNKNOWN
        }

        private fun resolveChatId(chatValue: String): ChatId? {
                chatValue.toLongOrNull()?.let { return ChatId.fromId(it) }

                if (chatValue.isNotBlank()) {
                        return ChatId.fromChannelUsername(chatValue)
                }

                logger.warn("Telegram chat_id '$chatValue' is not valid. Notifications disabled.")
                return null
        }

        private fun buildErrorKeywordMatchers(): Map<ErrorCategory, List<String>> {
                val defaults = mapOf(
                        ErrorCategory.NETWORK to listOf(
                                "could not resolve host",
                                "connection timed out",
                                "connection refused",
                                "network is unreachable",
                                "no route to host",
                                "temporary failure in name resolution",
                        ),
                        ErrorCategory.AUTHENTICATION to listOf(
                                "authentication failed",
                                "invalid username or password",
                                "permission denied",
                                "unauthorized",
                                "403 forbidden",
                                "could not read username",
                                "invalid credentials",
                        ),
                        ErrorCategory.GIT_ERROR to listOf(
                                "not a git repository",
                                "repository not found",
                                "404 not found",
                                "fatal: unable to access",
                                "remote: repository not found",
                                "does not appear to be a git",
                        ),
                        ErrorCategory.DISK_SPACE to listOf(
                                "no space left on device",
                                "disk quota exceeded",
                                "insufficient storage",
                        ),
                        ErrorCategory.RATE_LIMITING to listOf(
                                "rate limit",
                                "api limit",
                                "too many requests",
                                "retry after",
                                "429",
                        ),
                        ErrorCategory.SSL_TLS to listOf(
                                "ssl",
                                "tls",
                                "certificate",
                                "handshake failure",
                                "handshake failed",
                        ),
                )

                val merged = defaults.mapValues { it.value.toMutableSet() }.toMutableMap()

                config?.errorKeywords?.forEach { (categoryName, keywords) ->
                        val category = runCatching { ErrorCategory.valueOf(categoryName.uppercase()) }.getOrNull()
                        if (category == null) {
                                logger.warn("Unknown Telegram error category override: '$categoryName'")
                        } else {
                                merged.getOrPut(category) { mutableSetOf() }.addAll(keywords.map(String::lowercase))
                        }
                }

                return merged.mapValues { (_, values) -> values.toList() }
        }

        /**
         * Provides actionable recovery suggestions based on error category.
         */
        private fun getSuggestion(category: ErrorCategory): String {
                return when (category) {
			ErrorCategory.NETWORK -> "Check your internet connection and DNS settings. Verify the repository URL is accessible."
			ErrorCategory.AUTHENTICATION -> "Verify your credentials and token permissions. Ensure the token hasn't expired."
			ErrorCategory.GIT_ERROR -> "Verify the repository exists and the URL is correct. Check if the repository has been deleted or moved."
			ErrorCategory.DISK_SPACE -> "Free up disk space on your system. Consider archiving or removing old backups."
			ErrorCategory.RATE_LIMITING -> "Wait before retrying. Consider reducing sync frequency or using authentication to increase rate limits."
			ErrorCategory.SSL_TLS -> "Check SSL certificate configuration. Verify system certificates are up to date or configure cert_file in config."
			ErrorCategory.UNKNOWN -> "Check the error message for details. Verify your configuration and network connectivity."
		}
	}

	/**
	 * Sends a message to the configured chat.
	 */
	private fun sendMessage(message: String, context: String) {
		if (bot == null || chatId == null) {
			logger.lifecycle { "Skipping Telegram $context notification: service not initialized" }
			return
		}

		try {
			logger.lifecycle { "Sending Telegram $context notification to chat ${config?.chatId}" }
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

        private fun Duration.toHumanReadable(): String {
                val hours = this.seconds / 3600
                val minutes = (this.seconds % 3600) / 60
                val secs = this.seconds % 60
                return when {
                        hours > 0 -> "${hours}h ${minutes}m ${secs}s"
                        minutes > 0 -> "${minutes}m ${secs}s"
                        else -> "${secs}s"
                }
        }

}
