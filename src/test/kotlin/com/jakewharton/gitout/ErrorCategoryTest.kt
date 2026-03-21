package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

class ErrorCategoryTest {

	private fun classify(msg: String) = ErrorCategory.classify(msg)

	// --- REPOSITORY_ERROR (non-retryable) ---

	@Test fun `couldn't find remote ref classified as REPOSITORY_ERROR`() {
		assertThat(classify("Unable to sync https://github.com/user/repo.git: exit 1\nfatal: couldn't find remote ref refs/heads/main"))
			.isEqualTo(ErrorCategory.REPOSITORY_ERROR)
	}

	@Test fun `remote ref does not exist classified as REPOSITORY_ERROR`() {
		assertThat(classify("fatal: remote ref does not exist"))
			.isEqualTo(ErrorCategory.REPOSITORY_ERROR)
	}

	@Test fun `bad object classified as REPOSITORY_ERROR`() {
		assertThat(classify("error: bad object abc123"))
			.isEqualTo(ErrorCategory.REPOSITORY_ERROR)
	}

	@Test fun `is corrupt classified as REPOSITORY_ERROR`() {
		assertThat(classify("error: object file .git/objects/ab/cd1234 is corrupt"))
			.isEqualTo(ErrorCategory.REPOSITORY_ERROR)
	}

	@Test fun `repository is empty classified as REPOSITORY_ERROR`() {
		assertThat(classify("fatal: repository is empty"))
			.isEqualTo(ErrorCategory.REPOSITORY_ERROR)
	}

	@Test fun `nonexistent ref classified as REPOSITORY_ERROR`() {
		assertThat(classify("error: Remote HEAD refers to nonexistent ref"))
			.isEqualTo(ErrorCategory.REPOSITORY_ERROR)
	}

	// --- AUTH_ERROR (non-retryable) ---

	@Test fun `403 classified as AUTH_ERROR`() {
		assertThat(classify("fatal: unable to access 'https://github.com/user/repo.git/': The requested URL returned error: 403"))
			.isEqualTo(ErrorCategory.AUTH_ERROR)
	}

	@Test fun `repository not found classified as AUTH_ERROR`() {
		assertThat(classify("remote: Repository not found."))
			.isEqualTo(ErrorCategory.AUTH_ERROR)
	}

	@Test fun `repository not found in clone path classified as AUTH_ERROR`() {
		// Previously this was incorrectly excluded by a !contains("clone") guard
		assertThat(classify("Unable to sync https://github.com/user/clone-tools.git: exit 128\nremote: Repository not found."))
			.isEqualTo(ErrorCategory.AUTH_ERROR)
	}

	// --- RATE_LIMIT (retryable, 3x delay) ---

	@Test fun `429 classified as RATE_LIMIT`() {
		assertThat(classify("The requested URL returned error: 429"))
			.isEqualTo(ErrorCategory.RATE_LIMIT)
	}

	@Test fun `rate limit classified as RATE_LIMIT`() {
		assertThat(classify("API rate limit exceeded"))
			.isEqualTo(ErrorCategory.RATE_LIMIT)
	}

	@Test fun `too many requests classified as RATE_LIMIT`() {
		assertThat(classify("fatal: too many requests"))
			.isEqualTo(ErrorCategory.RATE_LIMIT)
	}

	// --- NETWORK_ERROR ordering fix: "connection timed out" should NOT be TIMEOUT ---

	@Test fun `connection timed out classified as NETWORK_ERROR not TIMEOUT`() {
		assertThat(classify("fatal: unable to connect: connection timed out"))
			.isEqualTo(ErrorCategory.NETWORK_ERROR)
	}

	@Test fun `generic timeout still classified as TIMEOUT`() {
		assertThat(classify("Unable to sync: timeout 10m"))
			.isEqualTo(ErrorCategory.TIMEOUT)
	}

	@Test fun `timed out classified as TIMEOUT`() {
		assertThat(classify("operation timed out"))
			.isEqualTo(ErrorCategory.TIMEOUT)
	}

	// --- SSL_ERROR: "tls" should not match unrelated strings ---

	@Test fun `ssl certificate error classified as SSL_ERROR`() {
		assertThat(classify("fatal: unable to access: SSL certificate problem: certificate has expired"))
			.isEqualTo(ErrorCategory.SSL_ERROR)
	}

	@Test fun `tls handshake error classified as SSL_ERROR`() {
		assertThat(classify("OpenSSL: error:14094410: tls handshake failure"))
			.isEqualTo(ErrorCategory.SSL_ERROR)
	}

	@Test fun `repo name containing tls not misclassified as SSL_ERROR`() {
		// A repo URL containing "tls" in the name should not trigger SSL_ERROR
		assertThat(classify("Unable to sync https://github.com/user/mytls-tools.git: exit 1\nfatal: couldn't find remote ref refs/heads/main"))
			.isEqualTo(ErrorCategory.REPOSITORY_ERROR)
	}

	// --- RATE_LIMIT retryability ---

	@Test fun `RATE_LIMIT is retryable`() {
		with(ErrorCategory.Companion) {
			assertThat(ErrorCategory.RATE_LIMIT.isRetryable()).isEqualTo(true)
		}
	}

	@Test fun `RATE_LIMIT has 3x delay multiplier`() {
		with(ErrorCategory.Companion) {
			assertThat(ErrorCategory.RATE_LIMIT.delayMultiplier()).isEqualTo(3.0)
		}
	}

	// --- displayName and suggestion ---

	@Test fun `REPOSITORY_ERROR displayName is Git Error`() {
		with(ErrorCategory.Companion) {
			assertThat(ErrorCategory.REPOSITORY_ERROR.displayName).isEqualTo("Git Error")
		}
	}

	@Test fun `RATE_LIMIT displayName is Rate Limiting`() {
		with(ErrorCategory.Companion) {
			assertThat(ErrorCategory.RATE_LIMIT.displayName).isEqualTo("Rate Limiting")
		}
	}
}
