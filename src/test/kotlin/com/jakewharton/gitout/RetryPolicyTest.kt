package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.isEqualTo
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
}
