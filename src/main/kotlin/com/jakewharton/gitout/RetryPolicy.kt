package com.jakewharton.gitout

import com.jakewharton.gitout.ErrorCategory.Companion.delayMultiplier
import com.jakewharton.gitout.ErrorCategory.Companion.isRetryable
import com.jakewharton.gitout.ErrorCategory.Companion.shouldUseHttp1Fallback
import kotlinx.coroutines.delay

/**
 * A configurable retry policy for handling transient failures in operations.
 *
 * This class provides a reusable mechanism to retry operations that may fail transiently,
 * such as network calls or git commands. It supports different backoff strategies,
 * comprehensive logging of retry attempts, and adaptive behavior based on error categories.
 *
 * Example usage:
 * ```
 * val retryPolicy = RetryPolicy(
 *     maxAttempts = 6,
 *     baseDelayMs = 5000L,
 *     backoffStrategy = BackoffStrategy.LINEAR,
 *     logger = logger
 * )
 *
 * val result = retryPolicy.execute { context ->
 *     // Check if we should use HTTP/1.1 fallback
 *     val useHttp1 = context.shouldUseHttp1Fallback
 *     performGitSync(useHttp1 = useHttp1)
 * }
 * ```
 *
 * @property maxAttempts The maximum number of attempts (including the initial attempt). Must be at least 1.
 * @property baseDelayMs The base delay in milliseconds between retry attempts. Must be non-negative.
 * @property backoffStrategy The strategy to use for calculating delays between retries.
 * @property logger The logger for recording retry attempts and failures.
 * @property adaptiveRetry Whether to enable adaptive retry behavior based on error categories.
 */
internal class RetryPolicy(
	private val maxAttempts: Int = 6,
	private val baseDelayMs: Long = 5000L,
	private val backoffStrategy: BackoffStrategy = BackoffStrategy.LINEAR,
	private val logger: Logger,
	private val adaptiveRetry: Boolean = true,
) {
	init {
		require(maxAttempts >= 1) { "maxAttempts must be at least 1, was: $maxAttempts" }
		require(baseDelayMs >= 0) { "baseDelayMs must be non-negative, was: $baseDelayMs" }
	}

	/**
	 * Strategies for calculating the delay between retry attempts.
	 */
	internal enum class BackoffStrategy {
		/**
		 * Linear backoff: delay increases linearly with each attempt.
		 * Delay = baseDelayMs * attempt
		 * Example with baseDelayMs=5000: 5s, 10s, 15s, 20s, 25s, 30s
		 */
		LINEAR,

		/**
		 * Exponential backoff: delay doubles with each attempt.
		 * Delay = baseDelayMs * 2^(attempt-1)
		 * Example with baseDelayMs=5000: 5s, 10s, 20s, 40s, 80s, 160s
		 */
		EXPONENTIAL,

		/**
		 * Constant backoff: delay remains the same for all attempts.
		 * Delay = baseDelayMs
		 * Example with baseDelayMs=5000: 5s, 5s, 5s, 5s, 5s, 5s
		 */
		CONSTANT
	}

	/**
	 * Context passed to the operation being retried.
	 * Contains information about the current retry state and recommended actions.
	 */
	data class RetryContext(
		val attempt: Int,
		val maxAttempts: Int,
		val shouldUseHttp1Fallback: Boolean,
		val lastErrorCategory: ErrorCategory?,
		val isRetry: Boolean,
	)

	/**
	 * Result of a retry operation containing additional metadata.
	 */
	data class RetryResult<T>(
		val value: T,
		val attempts: Int,
		val usedHttp1Fallback: Boolean,
		val errorCategories: List<ErrorCategory>,
	)

	/**
	 * Executes the given operation with automatic retry logic.
	 *
	 * The operation will be attempted up to [maxAttempts] times. If all attempts fail,
	 * an [IllegalStateException] will be thrown with details about the failure.
	 *
	 * Between attempts, the execution will be delayed according to the configured
	 * [backoffStrategy]. The first attempt has no delay.
	 *
	 * @param T The return type of the operation.
	 * @param operationDescription A human-readable description of the operation being retried,
	 *        used in log messages and exception messages. If null, a generic description is used.
	 * @param operation The suspend function to execute. Receives a [RetryContext] with the current
	 *        attempt number (1-indexed) and retry state information.
	 * @return The successful result of the operation.
	 * @throws IllegalStateException If all retry attempts are exhausted without success.
	 */
	internal suspend fun <T> execute(
		operationDescription: String? = null,
		operation: suspend (context: RetryContext) -> T,
	): T {
		return executeWithResult(operationDescription, operation).value
	}

	/**
	 * Executes the given operation with automatic retry logic and returns detailed result.
	 *
	 * Similar to [execute] but returns a [RetryResult] containing additional metadata
	 * about the retry process.
	 *
	 * @param T The return type of the operation.
	 * @param operationDescription A human-readable description of the operation being retried.
	 * @param operation The suspend function to execute.
	 * @return A [RetryResult] containing the successful result and retry metadata.
	 * @throws IllegalStateException If all retry attempts are exhausted without success.
	 */
	internal suspend fun <T> executeWithResult(
		operationDescription: String? = null,
		operation: suspend (context: RetryContext) -> T,
	): RetryResult<T> {
		var lastException: Exception? = null
		var shouldUseHttp1Fallback = false
		var lastErrorCategory: ErrorCategory? = null
		val errorCategories = mutableListOf<ErrorCategory>()

		for (attempt in 1..maxAttempts) {
			try {
				// Add delay before retry attempts (not on first attempt)
				if (attempt > 1) {
					val baseDelay = calculateDelay(attempt)
					val delayMultiplier = if (adaptiveRetry && lastErrorCategory != null) {
						lastErrorCategory!!.delayMultiplier()
					} else {
						1.0
					}
					val delayMs = (baseDelay * delayMultiplier).toLong()

					val fallbackInfo = if (shouldUseHttp1Fallback) " (using HTTP/1.1 fallback)" else ""
					logger.info { "Retry attempt $attempt/$maxAttempts${operationDescription?.let { " for $it" } ?: ""}$fallbackInfo" }
					delay(delayMs)
				}

				// Create context for the operation
				val context = RetryContext(
					attempt = attempt,
					maxAttempts = maxAttempts,
					shouldUseHttp1Fallback = shouldUseHttp1Fallback,
					lastErrorCategory = lastErrorCategory,
					isRetry = attempt > 1,
				)

				// Execute the operation
				val result = operation(context)

				return RetryResult(
					value = result,
					attempts = attempt,
					usedHttp1Fallback = shouldUseHttp1Fallback,
					errorCategories = errorCategories,
				)

			} catch (e: Exception) {
				lastException = e
				val description = operationDescription ?: "operation"

				// Classify the error for adaptive retry
				if (adaptiveRetry) {
					lastErrorCategory = ErrorCategory.classify(e.message)
					errorCategories.add(lastErrorCategory)

					// Check if we should enable HTTP/1.1 fallback
					if (!shouldUseHttp1Fallback && lastErrorCategory.shouldUseHttp1Fallback()) {
						shouldUseHttp1Fallback = true
						logger.info { "Detected ${lastErrorCategory.name} error, enabling HTTP/1.1 fallback for retry" }
					}

					// Check if this error type is retryable
					if (!lastErrorCategory.isRetryable()) {
						logger.warn("Attempt $attempt failed for $description with non-retryable error (${lastErrorCategory.name}): ${e.message}")
						break // Don't retry non-retryable errors
					}
				}

				logger.warn("Attempt $attempt failed for $description: ${e.message}")

				// If this was the last attempt, we'll throw outside the loop
				if (attempt == maxAttempts) {
					break
				}
			}
		}

		// All attempts exhausted
		val description = operationDescription ?: "operation"
		val categoryInfo = if (errorCategories.isNotEmpty()) {
			" (error categories: ${errorCategories.distinct().joinToString { it.name }})"
		} else {
			""
		}
		throw IllegalStateException(
			"Failed to complete $description after $maxAttempts attempts$categoryInfo",
			lastException
		)
	}

	/**
	 * Calculates the delay in milliseconds for a given retry attempt.
	 *
	 * @param attempt The current attempt number (1-indexed).
	 * @return The delay in milliseconds to wait before this attempt.
	 */
	private fun calculateDelay(attempt: Int): Long {
		return when (backoffStrategy) {
			BackoffStrategy.LINEAR -> baseDelayMs * attempt
			BackoffStrategy.EXPONENTIAL -> {
				// Exponential: baseDelayMs * 2^(attempt-1)
				// Use bitshift for efficiency: 1 shl (attempt - 1) = 2^(attempt-1)
				val multiplier = 1L shl (attempt - 1)
				baseDelayMs * multiplier
			}
			BackoffStrategy.CONSTANT -> baseDelayMs
		}
	}

	/**
	 * Returns a string representation of this retry policy for debugging.
	 */
	override fun toString(): String {
		return "RetryPolicy(maxAttempts=$maxAttempts, baseDelayMs=$baseDelayMs, backoffStrategy=$backoffStrategy, adaptiveRetry=$adaptiveRetry)"
	}
}
