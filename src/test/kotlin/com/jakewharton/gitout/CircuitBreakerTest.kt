package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class CircuitBreakerTest {

	@Test fun `circuit breaker starts closed`() {
		val breaker = StorageCircuitBreaker(threshold = 3)
		assertThat(breaker.isOpen()).isFalse()
	}

	@Test fun `consecutive storage failures below threshold do not open breaker`() {
		val breaker = StorageCircuitBreaker(threshold = 3)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		assertThat(breaker.isOpen()).isFalse()
	}

	@Test fun `consecutive storage failures at threshold open the breaker`() {
		val breaker = StorageCircuitBreaker(threshold = 3)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		val tripped = breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		assertThat(tripped).isTrue()
		assertThat(breaker.isOpen()).isTrue()
	}

	@Test fun `recordFailure returns true only on the trip call not on subsequent calls`() {
		val breaker = StorageCircuitBreaker(threshold = 2)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		val firstTrip = breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		val secondCall = breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		assertThat(firstTrip).isTrue()
		assertThat(secondCall).isFalse()
	}

	@Test fun `non-storage failure resets the consecutive counter`() {
		val breaker = StorageCircuitBreaker(threshold = 3)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		// A network error should reset the streak
		breaker.recordFailure(ErrorCategory.NETWORK_ERROR)
		// Two more storage failures — streak restarted from zero, so still below threshold
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		assertThat(breaker.isOpen()).isFalse()
	}

	@Test fun `non-storage failure does not open breaker even after many calls`() {
		val breaker = StorageCircuitBreaker(threshold = 3)
		repeat(10) { breaker.recordFailure(ErrorCategory.NETWORK_ERROR) }
		assertThat(breaker.isOpen()).isFalse()
	}

	@Test fun `recordSuccess resets the consecutive storage failure counter`() {
		val breaker = StorageCircuitBreaker(threshold = 3)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		breaker.recordSuccess()
		// Streak was reset; need threshold more failures to trip
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		assertThat(breaker.isOpen()).isFalse()
	}

	@Test fun `circuit breaker stays open after being tripped`() {
		val breaker = StorageCircuitBreaker(threshold = 1)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		assertThat(breaker.isOpen()).isTrue()
		// Success and non-storage failures do not close the breaker
		breaker.recordSuccess()
		breaker.recordFailure(ErrorCategory.NETWORK_ERROR)
		assertThat(breaker.isOpen()).isTrue()
	}

	@Test fun `auth error resets consecutive storage failure counter`() {
		val breaker = StorageCircuitBreaker(threshold = 3)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		breaker.recordFailure(ErrorCategory.AUTH_ERROR) // resets streak
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		// Only 2 consecutive storage failures after reset — should be closed
		assertThat(breaker.isOpen()).isFalse()
	}

	@Test fun `threshold of one trips on first storage failure`() {
		val breaker = StorageCircuitBreaker(threshold = 1)
		val tripped = breaker.recordFailure(ErrorCategory.STORAGE_ERROR)
		assertThat(tripped).isTrue()
		assertThat(breaker.isOpen()).isTrue()
	}
}
