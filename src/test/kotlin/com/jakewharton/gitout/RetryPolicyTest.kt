package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.message
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RetryPolicyTest {
	private val quietLogger = Logger(quiet = true, level = 0)

	@Test
	fun `successful execution on first attempt returns result`() = runTest {
		val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 100L, logger = quietLogger)
		var attempts = 0

		val result = policy.execute("test operation") { context ->
			attempts = context.attempt
			"success"
		}

		assertThat(result).isEqualTo("success")
		assertThat(attempts).isEqualTo(1)
	}

	@Test
	fun `retry succeeds on second attempt`() = runTest {
		val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 10L, logger = quietLogger)
		var attempts = 0

		val result = policy.execute("test operation") { context ->
			attempts = context.attempt
			if (context.attempt < 2) {
				throw RuntimeException("Transient failure")
			}
			"success after retry"
		}

		assertThat(result).isEqualTo("success after retry")
		assertThat(attempts).isEqualTo(2)
	}

	@Test
	fun `retry succeeds on last attempt`() = runTest {
		val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 10L, logger = quietLogger)
		var attempts = 0

		val result = policy.execute("test operation") { context ->
			attempts = context.attempt
			if (context.attempt < 3) {
				throw RuntimeException("Transient failure")
			}
			"success on last attempt"
		}

		assertThat(result).isEqualTo("success on last attempt")
		assertThat(attempts).isEqualTo(3)
	}

	@Test
	fun `all attempts exhausted throws IllegalStateException with cause`() = runTest {
		val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 10L, logger = quietLogger)
		val originalException = RuntimeException("Persistent failure")

		val exception = try {
			policy.execute("failing operation") { _ ->
				throw originalException
			}
			null
		} catch (e: IllegalStateException) {
			e
		}

		assertThat(exception).isNotNull()
		assertThat(exception!!).message().isNotNull()
	}

	@Test
	fun `maxAttempts of 1 means no retries`() = runTest {
		val policy = RetryPolicy(maxAttempts = 1, baseDelayMs = 10L, logger = quietLogger)
		var attempts = 0

		val exception = try {
			policy.execute("single attempt") { context ->
				attempts = context.attempt
				throw RuntimeException("First failure")
			}
			null
		} catch (e: IllegalStateException) {
			e
		}

		assertThat(attempts).isEqualTo(1)
		assertThat(exception).isNotNull()
	}

	@Test(expected = IllegalArgumentException::class)
	fun `maxAttempts less than 1 throws IllegalArgumentException`() {
		RetryPolicy(maxAttempts = 0, baseDelayMs = 100L, logger = quietLogger)
	}

	@Test(expected = IllegalArgumentException::class)
	fun `negative baseDelayMs throws IllegalArgumentException`() {
		RetryPolicy(maxAttempts = 3, baseDelayMs = -1L, logger = quietLogger)
	}

	@Test
	fun `zero baseDelayMs is allowed`() = runTest {
		val policy = RetryPolicy(maxAttempts = 2, baseDelayMs = 0L, logger = quietLogger)
		var attempts = 0

		val result = policy.execute { context ->
			attempts = context.attempt
			if (context.attempt < 2) throw RuntimeException("fail")
			"success"
		}

		assertThat(result).isEqualTo("success")
		assertThat(attempts).isEqualTo(2)
	}

	@Test
	fun `toString includes configuration`() {
		val policy = RetryPolicy(
			maxAttempts = 5,
			baseDelayMs = 1000L,
			backoffStrategy = RetryPolicy.BackoffStrategy.EXPONENTIAL,
			logger = quietLogger
		)

		val str = policy.toString()
		assertThat(str).isEqualTo("RetryPolicy(maxAttempts=5, baseDelayMs=1000, backoffStrategy=EXPONENTIAL, adaptiveRetry=true)")
	}

	@Test
	fun `execute with null description uses generic message`() = runTest {
		val policy = RetryPolicy(maxAttempts = 2, baseDelayMs = 10L, logger = quietLogger)

		val exception = try {
			policy.execute { throw RuntimeException("fail") }
			null
		} catch (e: IllegalStateException) {
			e
		}

		assertThat(exception).isNotNull()
	}

	@Test
	fun `tracks correct attempt number passed to operation`() = runTest {
		val policy = RetryPolicy(maxAttempts = 4, baseDelayMs = 10L, logger = quietLogger)
		val recordedAttempts = mutableListOf<Int>()

		policy.execute("tracking") { context ->
			recordedAttempts.add(context.attempt)
			if (context.attempt < 3) throw RuntimeException("not yet")
			"done"
		}

		assertThat(recordedAttempts).isEqualTo(listOf(1, 2, 3))
	}

	@Test
	fun `exhausted retries throws SyncFailureException`() = runTest {
		val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 10L, logger = quietLogger)

		val exception = try {
			policy.execute("failing operation") { throw RuntimeException("Persistent failure") }
			null
		} catch (e: SyncFailureException) {
			e
		} catch (e: IllegalStateException) {
			null // should not reach here
		}

		assertThat(exception).isNotNull()
	}

	@Test
	fun `SyncFailureException records attempt count`() = runTest {
		val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 10L, logger = quietLogger)

		val exception = try {
			policy.execute("failing operation") { throw RuntimeException("Persistent failure") }
			null
		} catch (e: SyncFailureException) {
			e
		}

		assertThat(exception).isNotNull()
		assertThat(exception!!.attemptCount).isEqualTo(3)
	}

	@Test
	fun `SyncFailureException records error categories`() = runTest {
		val policy = RetryPolicy(maxAttempts = 2, baseDelayMs = 10L, logger = quietLogger)

		val exception = try {
			policy.execute("failing operation") {
				throw RuntimeException("connection reset by peer")
			}
			null
		} catch (e: SyncFailureException) {
			e
		}

		assertThat(exception).isNotNull()
		assertThat(exception!!.errorCategories.size).isGreaterThan(0)
		assertThat(exception.errorCategories.first()).isEqualTo(ErrorCategory.NETWORK_ERROR)
	}

	@Test
	fun `non-retryable error stops immediately and throws SyncFailureException`() = runTest {
		val policy = RetryPolicy(maxAttempts = 6, baseDelayMs = 10L, logger = quietLogger)
		var attempts = 0

		val exception = try {
			policy.execute("auth failing") { context ->
				attempts = context.attempt
				throw RuntimeException("couldn't find remote ref refs/heads/main")
			}
			null
		} catch (e: SyncFailureException) {
			e
		}

		// REPOSITORY_ERROR is non-retryable - should stop after 1 attempt
		assertThat(attempts).isEqualTo(1)
		assertThat(exception).isNotNull()
		assertThat(exception!!.errorCategories.first()).isEqualTo(ErrorCategory.REPOSITORY_ERROR)
	}

	@Test
	fun `non-retryable error reports actual attempt count not maxAttempts`() = runTest {
		val policy = RetryPolicy(maxAttempts = 6, baseDelayMs = 10L, logger = quietLogger)

		val exception = try {
			policy.execute("auth failing") {
				throw RuntimeException("authentication failed")
			}
			null
		} catch (e: SyncFailureException) {
			e
		}

		// Should report 1 actual attempt, not the configured 6
		assertThat(exception).isNotNull()
		assertThat(exception!!.attemptCount).isEqualTo(1)
	}

	@Test
	fun `EXPONENTIAL backoff doubles delay each attempt`() {
		val policy = RetryPolicy(
			maxAttempts = 5,
			baseDelayMs = 100L,
			backoffStrategy = RetryPolicy.BackoffStrategy.EXPONENTIAL,
			logger = quietLogger,
		)
		// EXPONENTIAL: baseDelayMs * 2^(attempt-1)
		// attempt 2: 100 * 2^1 = 200ms, attempt 3: 100 * 2^2 = 400ms, attempt 4: 100 * 2^3 = 800ms
		assertThat(policy.calculateDelayForTest(2)).isEqualTo(200L)
		assertThat(policy.calculateDelayForTest(3)).isEqualTo(400L)
		assertThat(policy.calculateDelayForTest(4)).isEqualTo(800L)
	}

	@Test
	fun `CONSTANT backoff always uses base delay`() {
		val policy = RetryPolicy(
			maxAttempts = 5,
			baseDelayMs = 100L,
			backoffStrategy = RetryPolicy.BackoffStrategy.CONSTANT,
			logger = quietLogger,
		)
		assertThat(policy.calculateDelayForTest(2)).isEqualTo(100L)
		assertThat(policy.calculateDelayForTest(3)).isEqualTo(100L)
		assertThat(policy.calculateDelayForTest(4)).isEqualTo(100L)
	}

	@Test
	fun `LINEAR backoff multiplies delay by attempt number`() {
		val policy = RetryPolicy(
			maxAttempts = 5,
			baseDelayMs = 100L,
			backoffStrategy = RetryPolicy.BackoffStrategy.LINEAR,
			logger = quietLogger,
		)
		// LINEAR: baseDelayMs * attempt
		assertThat(policy.calculateDelayForTest(2)).isEqualTo(200L)
		assertThat(policy.calculateDelayForTest(3)).isEqualTo(300L)
	}
}
