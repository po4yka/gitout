package com.jakewharton.gitout

import kotlinx.coroutines.delay

/**
 * A configurable retry policy for handling transient failures in operations.
 *
 * This class provides a reusable mechanism to retry operations that may fail transiently,
 * such as network calls or git commands. It supports different backoff strategies and
 * comprehensive logging of retry attempts.
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
 * val result = retryPolicy.execute { attempt ->
 *     // Your operation here
 *     performGitSync()
 * }
 * ```
 *
 * @property maxAttempts The maximum number of attempts (including the initial attempt). Must be at least 1.
 * @property baseDelayMs The base delay in milliseconds between retry attempts. Must be non-negative.
 * @property backoffStrategy The strategy to use for calculating delays between retries.
 * @property logger The logger for recording retry attempts and failures.
 */
internal class RetryPolicy(
	private val maxAttempts: Int = 6,
	private val baseDelayMs: Long = 5000L,
	private val backoffStrategy: BackoffStrategy = BackoffStrategy.LINEAR,
	private val logger: Logger,
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
	 * @param operation The suspend function to execute. Receives the current attempt number
	 *        (1-indexed) as a parameter.
	 * @return The successful result of the operation.
	 * @throws IllegalStateException If all retry attempts are exhausted without success.
	 */
	internal suspend fun <T> execute(
		operationDescription: String? = null,
		operation: suspend (attempt: Int) -> T,
	): T {
		var lastException: Exception? = null

		for (attempt in 1..maxAttempts) {
			try {
				// Add delay before retry attempts (not on first attempt)
				if (attempt > 1) {
					val delayMs = calculateDelay(attempt)
					logger.info { "Retry attempt $attempt/$maxAttempts${operationDescription?.let { " for $it" } ?: ""}" }
					delay(delayMs)
				}

				// Execute the operation
				return operation(attempt)

			} catch (e: Exception) {
				lastException = e
				val description = operationDescription ?: "operation"
				logger.warn("Attempt $attempt failed for $description: ${e.message}")

				// If this was the last attempt, we'll throw outside the loop
				if (attempt == maxAttempts) {
					break
				}
			}
		}

		// All attempts exhausted
		val description = operationDescription ?: "operation"
		throw IllegalStateException(
			"Failed to complete $description after $maxAttempts attempts",
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
		return "RetryPolicy(maxAttempts=$maxAttempts, baseDelayMs=$baseDelayMs, backoffStrategy=$backoffStrategy)"
	}
}
