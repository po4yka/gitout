package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Unit tests for the RetryPolicy class.
 *
 * These tests verify:
 * - Retry logic with different success/failure scenarios
 * - Backoff strategy calculations (linear, exponential, constant)
 * - Error handling and exception propagation
 * - Edge cases and parameter validation
 */
class RetryPolicyTest {

	private fun createTestLogger(): Logger {
		return Logger(quiet = true, level = 0)
	}

	@Test
	fun successOnFirstAttempt() = runBlocking {
		val logger = createTestLogger()
		val retryPolicy = RetryPolicy(
			maxAttempts = 6,
			baseDelayMs = 100,
			backoffStrategy = RetryPolicy.BackoffStrategy.LINEAR,
			logger = logger
		)

		var callCount = 0
		val result = retryPolicy.execute { attempt ->
			callCount++
			"success"
		}

		assertThat(result).isEqualTo("success")
		assertThat(callCount).isEqualTo(1)
	}

	@Test
	fun successAfterThreeRetries() = runBlocking {
		val logger = createTestLogger()
		val retryPolicy = RetryPolicy(
			maxAttempts = 6,
			baseDelayMs = 50,
			backoffStrategy = RetryPolicy.BackoffStrategy.LINEAR,
			logger = logger
		)

		var callCount = 0
		val result = retryPolicy.execute { attempt ->
			callCount++
			if (callCount < 4) {
				throw RuntimeException("Transient failure $callCount")
			}
			"success after 3 retries"
		}

		assertThat(result).isEqualTo("success after 3 retries")
		assertThat(callCount).isEqualTo(4)
	}

	@Test
	fun failureAfterMaxRetries() = runBlocking {
		val logger = createTestLogger()
		val retryPolicy = RetryPolicy(
			maxAttempts = 3,
			baseDelayMs = 10,
			backoffStrategy = RetryPolicy.BackoffStrategy.LINEAR,
			logger = logger
		)

		var callCount = 0
		var caughtException: Exception? = null

		try {
			retryPolicy.execute<String> { attempt ->
				callCount++
				throw RuntimeException("Always fails")
			}
		} catch (e: Exception) {
			caughtException = e
		}

		assertThat(caughtException).isNotNull().isInstanceOf(IllegalStateException::class)
		assertThat(caughtException?.message).isNotNull().contains("Failed to complete", "after 3 attempts")
		assertThat(callCount).isEqualTo(3)
	}

	@Test
	fun linearBackoffTiming() = runBlocking {
		val logger = createTestLogger()
		val baseDelayMs = 100L
		val retryPolicy = RetryPolicy(
			maxAttempts = 4,
			baseDelayMs = baseDelayMs,
			backoffStrategy = RetryPolicy.BackoffStrategy.LINEAR,
			logger = logger
		)

		val timestamps = mutableListOf<Long>()
		var callCount = 0

		try {
			retryPolicy.execute<Unit> { attempt ->
				timestamps.add(System.currentTimeMillis())
				callCount++
				throw RuntimeException("Fail to test timing")
			}
		} catch (e: Exception) {
			// Expected
		}

		assertThat(callCount).isEqualTo(4)
		assertThat(timestamps.size).isEqualTo(4)

		// Check delays between attempts
		// Attempt 1: no delay
		// Attempt 2: baseDelayMs * 2 = 200ms
		// Attempt 3: baseDelayMs * 3 = 300ms
		// Attempt 4: baseDelayMs * 4 = 400ms

		val delay1to2 = timestamps[1] - timestamps[0]
		val delay2to3 = timestamps[2] - timestamps[1]
		val delay3to4 = timestamps[3] - timestamps[2]

		// Allow 50ms tolerance for timing variations
		val tolerance = 50L

		assertThat(delay1to2).isGreaterThanOrEqualTo(baseDelayMs * 2 - tolerance)
		assertThat(delay1to2).isLessThanOrEqualTo(baseDelayMs * 2 + tolerance)

		assertThat(delay2to3).isGreaterThanOrEqualTo(baseDelayMs * 3 - tolerance)
		assertThat(delay2to3).isLessThanOrEqualTo(baseDelayMs * 3 + tolerance)

		assertThat(delay3to4).isGreaterThanOrEqualTo(baseDelayMs * 4 - tolerance)
		assertThat(delay3to4).isLessThanOrEqualTo(baseDelayMs * 4 + tolerance)
	}

	@Test
	fun exponentialBackoffCalculation() = runBlocking {
		val logger = createTestLogger()
		val baseDelayMs = 50L
		val retryPolicy = RetryPolicy(
			maxAttempts = 5,
			baseDelayMs = baseDelayMs,
			backoffStrategy = RetryPolicy.BackoffStrategy.EXPONENTIAL,
			logger = logger
		)

		val timestamps = mutableListOf<Long>()

		try {
			retryPolicy.execute<Unit> { attempt ->
				timestamps.add(System.currentTimeMillis())
				throw RuntimeException("Fail to test timing")
			}
		} catch (e: Exception) {
			// Expected
		}

		assertThat(timestamps.size).isEqualTo(5)

		// Check exponential delays
		// Attempt 1: no delay
		// Attempt 2: baseDelayMs * 2^1 = 100ms
		// Attempt 3: baseDelayMs * 2^2 = 200ms
		// Attempt 4: baseDelayMs * 2^3 = 400ms
		// Attempt 5: baseDelayMs * 2^4 = 800ms

		val delay1to2 = timestamps[1] - timestamps[0]
		val delay2to3 = timestamps[2] - timestamps[1]
		val delay3to4 = timestamps[3] - timestamps[2]
		val delay4to5 = timestamps[4] - timestamps[3]

		val tolerance = 50L

		assertThat(delay1to2).isGreaterThanOrEqualTo(baseDelayMs * 2 - tolerance)
		assertThat(delay2to3).isGreaterThanOrEqualTo(baseDelayMs * 4 - tolerance)
		assertThat(delay3to4).isGreaterThanOrEqualTo(baseDelayMs * 8 - tolerance)
		assertThat(delay4to5).isGreaterThanOrEqualTo(baseDelayMs * 16 - tolerance)
	}

	@Test
	fun constantBackoffTiming() = runBlocking {
		val logger = createTestLogger()
		val baseDelayMs = 100L
		val retryPolicy = RetryPolicy(
			maxAttempts = 4,
			baseDelayMs = baseDelayMs,
			backoffStrategy = RetryPolicy.BackoffStrategy.CONSTANT,
			logger = logger
		)

		val timestamps = mutableListOf<Long>()

		try {
			retryPolicy.execute<Unit> { attempt ->
				timestamps.add(System.currentTimeMillis())
				throw RuntimeException("Fail to test timing")
			}
		} catch (e: Exception) {
			// Expected
		}

		assertThat(timestamps.size).isEqualTo(4)

		// Check constant delays (all should be ~baseDelayMs)
		val delay1to2 = timestamps[1] - timestamps[0]
		val delay2to3 = timestamps[2] - timestamps[1]
		val delay3to4 = timestamps[3] - timestamps[2]

		val tolerance = 50L

		assertThat(delay1to2).isGreaterThanOrEqualTo(baseDelayMs - tolerance)
		assertThat(delay1to2).isLessThanOrEqualTo(baseDelayMs + tolerance)

		assertThat(delay2to3).isGreaterThanOrEqualTo(baseDelayMs - tolerance)
		assertThat(delay2to3).isLessThanOrEqualTo(baseDelayMs + tolerance)

		assertThat(delay3to4).isGreaterThanOrEqualTo(baseDelayMs - tolerance)
		assertThat(delay3to4).isLessThanOrEqualTo(baseDelayMs + tolerance)
	}

	@Test
	fun operationDescriptionInErrorMessage() = runBlocking {
		val logger = createTestLogger()
		val retryPolicy = RetryPolicy(
			maxAttempts = 2,
			baseDelayMs = 10,
			logger = logger
		)

		var caughtException: Exception? = null

		try {
			retryPolicy.execute<String>(operationDescription = "https://example.com/repo.git") { attempt ->
				throw RuntimeException("Connection refused")
			}
		} catch (e: Exception) {
			caughtException = e
		}

		assertThat(caughtException).isNotNull().isInstanceOf(IllegalStateException::class)
		assertThat(caughtException?.message).isNotNull().contains("https://example.com/repo.git", "after 2 attempts")
	}

	@Test
	fun maxAttemptsValidation() {
		val logger = createTestLogger()

		var exception1: Exception? = null
		try {
			RetryPolicy(
				maxAttempts = 0,
				baseDelayMs = 1000,
				logger = logger
			)
		} catch (e: Exception) {
			exception1 = e
		}
		assertThat(exception1).isNotNull().isInstanceOf(IllegalArgumentException::class)
		assertThat(exception1?.message).isNotNull().contains("maxAttempts must be at least 1")

		var exception2: Exception? = null
		try {
			RetryPolicy(
				maxAttempts = -5,
				baseDelayMs = 1000,
				logger = logger
			)
		} catch (e: Exception) {
			exception2 = e
		}
		assertThat(exception2).isNotNull().isInstanceOf(IllegalArgumentException::class)
		assertThat(exception2?.message).isNotNull().contains("maxAttempts must be at least 1")
	}

	@Test
	fun baseDelayValidation() {
		val logger = createTestLogger()

		var caughtException: Exception? = null
		try {
			RetryPolicy(
				maxAttempts = 3,
				baseDelayMs = -100,
				logger = logger
			)
		} catch (e: Exception) {
			caughtException = e
		}

		assertThat(caughtException).isNotNull().isInstanceOf(IllegalArgumentException::class)
		assertThat(caughtException?.message).isNotNull().contains("baseDelayMs must be non-negative")
	}

	@Test
	fun zeroDelayIsAllowed() = runBlocking {
		val logger = createTestLogger()
		val retryPolicy = RetryPolicy(
			maxAttempts = 3,
			baseDelayMs = 0, // No delay between retries
			logger = logger
		)

		var callCount = 0
		val startTime = System.currentTimeMillis()

		try {
			retryPolicy.execute<Unit> { attempt ->
				callCount++
				throw RuntimeException("Fail")
			}
		} catch (e: Exception) {
			// Expected
		}

		val elapsed = System.currentTimeMillis() - startTime

		assertThat(callCount).isEqualTo(3)
		// Should complete quickly with no delays
		assertThat(elapsed).isLessThanOrEqualTo(100)
	}

	@Test
	fun singleAttemptOnlyNoRetries() = runBlocking {
		val logger = createTestLogger()
		val retryPolicy = RetryPolicy(
			maxAttempts = 1,
			baseDelayMs = 1000,
			logger = logger
		)

		var callCount = 0
		var caughtException: Exception? = null

		try {
			retryPolicy.execute<String> { attempt ->
				callCount++
				throw RuntimeException("Immediate failure")
			}
		} catch (e: Exception) {
			caughtException = e
		}

		assertThat(caughtException).isNotNull().isInstanceOf(IllegalStateException::class)
		// Should only be called once, no retries
		assertThat(callCount).isEqualTo(1)
	}

	@Test
	fun attemptNumberIsPassedCorrectly() = runBlocking {
		val logger = createTestLogger()
		val retryPolicy = RetryPolicy(
			maxAttempts = 5,
			baseDelayMs = 10,
			logger = logger
		)

		val attemptNumbers = mutableListOf<Int>()

		retryPolicy.execute { attempt ->
			attemptNumbers.add(attempt)
			if (attempt < 3) {
				throw RuntimeException("Fail on attempts 1 and 2")
			}
			"success on attempt 3"
		}

		assertThat(attemptNumbers).isEqualTo(listOf(1, 2, 3))
	}

	@Test
	fun returnValueIsPassedThrough() = runBlocking {
		val logger = createTestLogger()
		val retryPolicy = RetryPolicy(logger = logger)

		data class Result(val status: String, val code: Int)

		val result = retryPolicy.execute { attempt ->
			Result("ok", 200)
		}

		assertThat(result.status).isEqualTo("ok")
		assertThat(result.code).isEqualTo(200)
	}

	@Test
	fun nullReturnValueIsAllowed() = runBlocking {
		val logger = createTestLogger()
		val retryPolicy = RetryPolicy(logger = logger)

		val result = retryPolicy.execute<String?> { attempt ->
			null
		}

		assertThat(result).isEqualTo(null)
	}

	@Test
	fun toStringShowsConfiguration() {
		val logger = createTestLogger()
		val retryPolicy = RetryPolicy(
			maxAttempts = 10,
			baseDelayMs = 2000,
			backoffStrategy = RetryPolicy.BackoffStrategy.EXPONENTIAL,
			logger = logger
		)

		val string = retryPolicy.toString()

		assertThat(string).contains("maxAttempts=10")
		assertThat(string).contains("baseDelayMs=2000")
		assertThat(string).contains("EXPONENTIAL")
	}

	@Test
	fun defaultParametersMatch() {
		val logger = createTestLogger()
		val retryPolicy = RetryPolicy(logger = logger)

		// Verify defaults match the original Engine behavior
		val string = retryPolicy.toString()
		assertThat(string).contains("maxAttempts=6")
		assertThat(string).contains("baseDelayMs=5000")
		assertThat(string).contains("LINEAR")
	}

	@Test
	fun differentExceptionTypesAreHandled() = runBlocking {
		val logger = createTestLogger()
		val retryPolicy = RetryPolicy(
			maxAttempts = 4,
			baseDelayMs = 10,
			logger = logger
		)

		var callCount = 0
		val result = retryPolicy.execute { attempt ->
			callCount++
			when (callCount) {
				1 -> throw IllegalStateException("State error")
				2 -> throw RuntimeException("Runtime error")
				3 -> throw IllegalArgumentException("Argument error")
				else -> "success"
			}
		}

		assertThat(result).isEqualTo("success")
		assertThat(callCount).isEqualTo(4)
	}

	@Test
	fun lastExceptionIsCapturedInFinalError() = runBlocking {
		val logger = createTestLogger()
		val retryPolicy = RetryPolicy(
			maxAttempts = 3,
			baseDelayMs = 10,
			logger = logger
		)

		val expectedMessage = "Final failure message"
		var caughtException: Exception? = null

		try {
			retryPolicy.execute<Unit> { attempt ->
				if (attempt < 3) {
					throw RuntimeException("Early failure $attempt")
				} else {
					throw RuntimeException(expectedMessage)
				}
			}
		} catch (e: Exception) {
			caughtException = e
		}

		assertThat(caughtException).isNotNull().isInstanceOf(IllegalStateException::class)
		assertThat(caughtException?.message).isNotNull().contains("after 3 attempts")
	}
}
