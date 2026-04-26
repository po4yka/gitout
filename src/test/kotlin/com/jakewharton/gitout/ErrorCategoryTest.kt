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

	// --- NETWORK_ERROR: "fatal: unable to access" with network causes ---

	@Test fun `unable to access with failed to connect classified as NETWORK_ERROR`() {
		assertThat(classify("Unable to sync https://github.com/facebook/redex.git into /data/github/clone/facebook/redex: exit 128\nfatal: unable to access 'https://github.com/facebook/redex.git/': Failed to connect to github.com port 443 after 134329 ms: Could not connect to server"))
			.isEqualTo(ErrorCategory.NETWORK_ERROR)
	}

	@Test fun `unable to access with could not connect classified as NETWORK_ERROR`() {
		assertThat(classify("fatal: unable to access 'https://github.com/user/repo.git/': Could not connect to server"))
			.isEqualTo(ErrorCategory.NETWORK_ERROR)
	}

	@Test fun `unable to access with could not resolve host classified as NETWORK_ERROR`() {
		assertThat(classify("fatal: unable to access 'https://github.com/user/repo.git/': Could not resolve host: github.com"))
			.isEqualTo(ErrorCategory.NETWORK_ERROR)
	}

	@Test fun `unable to access with 403 still classified as AUTH_ERROR`() {
		assertThat(classify("fatal: unable to access 'https://github.com/user/repo.git/': The requested URL returned error: 403"))
			.isEqualTo(ErrorCategory.AUTH_ERROR)
	}

	@Test fun `does not appear to be a git repository still classified as REPOSITORY_ERROR`() {
		assertThat(classify("fatal: does not appear to be a git repository"))
			.isEqualTo(ErrorCategory.REPOSITORY_ERROR)
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

	// --- Null and empty inputs ---

	@Test fun `null message classified as UNKNOWN`() {
		assertThat(ErrorCategory.classify(null)).isEqualTo(ErrorCategory.UNKNOWN)
	}

	@Test fun `empty message classified as UNKNOWN`() {
		assertThat(classify("")).isEqualTo(ErrorCategory.UNKNOWN)
	}

	// --- HTTP2_ERROR ---

	@Test fun `curl 92 classified as HTTP2_ERROR`() {
		assertThat(classify("error: RPC failed; curl 92 HTTP/2 stream 0 was not closed cleanly: PROTOCOL_ERROR"))
			.isEqualTo(ErrorCategory.HTTP2_ERROR)
	}

	@Test fun `http2 classified as HTTP2_ERROR`() {
		assertThat(classify("fatal: unable to access '...': HTTP/2 stream 1 was not closed cleanly"))
			.isEqualTo(ErrorCategory.HTTP2_ERROR)
	}

	@Test fun `curl 56 classified as HTTP2_ERROR`() {
		assertThat(classify("error: RPC failed; curl 56 GnuTLS recv error (-9): A TLS packet with unexpected length was received."))
			.isEqualTo(ErrorCategory.HTTP2_ERROR)
	}

	// --- NETWORK_ERROR - common git errors ---

	@Test fun `remote end hung up unexpectedly classified as NETWORK_ERROR`() {
		assertThat(classify("fatal: the remote end hung up unexpectedly"))
			.isEqualTo(ErrorCategory.NETWORK_ERROR)
	}

	@Test fun `broken pipe classified as NETWORK_ERROR`() {
		assertThat(classify("error: send-pack: send failure: Broken pipe"))
			.isEqualTo(ErrorCategory.NETWORK_ERROR)
	}

	@Test fun `internal server error classified as NETWORK_ERROR`() {
		assertThat(classify("fatal: repository 'https://github.com/org/repo.git/' not found\nremote: Internal server error"))
			.isEqualTo(ErrorCategory.NETWORK_ERROR)
	}

	@Test fun `service unavailable classified as NETWORK_ERROR`() {
		assertThat(classify("fatal: unable to access '...': The requested URL returned error: Service Unavailable"))
			.isEqualTo(ErrorCategory.NETWORK_ERROR)
	}

	@Test fun `early eof classified as NETWORK_ERROR after reclassification`() {
		assertThat(classify("fatal: early EOF\nfatal: index-pack failed"))
			.isEqualTo(ErrorCategory.NETWORK_ERROR)
	}

	@Test fun `fetch-pack classified as NETWORK_ERROR after reclassification`() {
		assertThat(classify("error: fetch-pack: invalid index-pack output"))
			.isEqualTo(ErrorCategory.NETWORK_ERROR)
	}

	// --- SSL_ERROR - GnuTLS ---

	@Test fun `gnutls_handshake failure classified as SSL_ERROR`() {
		assertThat(classify("fatal: unable to access '...': gnutls_handshake() failed: Error in the pull function."))
			.isEqualTo(ErrorCategory.SSL_ERROR)
	}

	// --- STORAGE_ERROR ---

	@Test fun `no space left classified as STORAGE_ERROR`() {
		assertThat(classify("error: object file .git/objects/xx/yy is empty\nfatal: no space left on device"))
			.isEqualTo(ErrorCategory.STORAGE_ERROR)
	}

	@Test fun `out of memory classified as STORAGE_ERROR`() {
		assertThat(classify("fatal: Out of memory, malloc failed"))
			.isEqualTo(ErrorCategory.STORAGE_ERROR)
	}

	@Test fun `unable to sync with I_O error classified as STORAGE_ERROR`() {
		assertThat(classify("Unable to sync https://github.com/foo/bar.git into /data/github/clone/foo: I/O error"))
			.isEqualTo(ErrorCategory.STORAGE_ERROR)
	}

	@Test fun `path with I_O error classified as STORAGE_ERROR`() {
		assertThat(classify("/data/github/clone/foo: I/O error"))
			.isEqualTo(ErrorCategory.STORAGE_ERROR)
	}

	@Test fun `input output error classified as STORAGE_ERROR`() {
		assertThat(classify("Input/output error"))
			.isEqualTo(ErrorCategory.STORAGE_ERROR)
	}

	@Test fun `read only file system classified as STORAGE_ERROR`() {
		assertThat(classify("cannot create directory: Read-only file system"))
			.isEqualTo(ErrorCategory.STORAGE_ERROR)
	}

	@Test fun `structure needs cleaning classified as STORAGE_ERROR`() {
		assertThat(classify("Structure needs cleaning"))
			.isEqualTo(ErrorCategory.STORAGE_ERROR)
	}

	@Test fun `stale file handle classified as STORAGE_ERROR`() {
		assertThat(classify("Stale file handle"))
			.isEqualTo(ErrorCategory.STORAGE_ERROR)
	}

	@Test fun `STORAGE_ERROR is not retryable for I_O error`() {
		with(ErrorCategory.Companion) {
			assertThat(ErrorCategory.STORAGE_ERROR.isRetryable()).isEqualTo(false)
		}
	}

	// --- False positive guards for 429 and 403 ---

	@Test fun `repo name containing 429 not classified as RATE_LIMIT`() {
		assertThat(classify("Unable to sync https://github.com/user/proj-429-tools.git: exit 1\nfatal: couldn't find remote ref refs/heads/main"))
			.isEqualTo(ErrorCategory.REPOSITORY_ERROR)
	}

	@Test fun `repo name containing 403 not classified as AUTH_ERROR`() {
		assertThat(classify("Unable to sync https://github.com/user/http-403-handler.git: exit 1\nfatal: couldn't find remote ref refs/heads/main"))
			.isEqualTo(ErrorCategory.REPOSITORY_ERROR)
	}

	@Test fun `actual 429 response still classified as RATE_LIMIT`() {
		assertThat(classify("fatal: unable to access '...': The requested URL returned error: 429"))
			.isEqualTo(ErrorCategory.RATE_LIMIT)
	}

	@Test fun `actual 403 response still classified as AUTH_ERROR`() {
		assertThat(classify("fatal: unable to access '...': The requested URL returned error: 403"))
			.isEqualTo(ErrorCategory.AUTH_ERROR)
	}

	// --- isRetryable for all categories ---

	@Test fun `HTTP2_ERROR is retryable`() {
		with(ErrorCategory.Companion) { assertThat(ErrorCategory.HTTP2_ERROR.isRetryable()).isEqualTo(true) }
	}

	@Test fun `NETWORK_ERROR is retryable`() {
		with(ErrorCategory.Companion) { assertThat(ErrorCategory.NETWORK_ERROR.isRetryable()).isEqualTo(true) }
	}

	@Test fun `TIMEOUT is retryable`() {
		with(ErrorCategory.Companion) { assertThat(ErrorCategory.TIMEOUT.isRetryable()).isEqualTo(true) }
	}

	@Test fun `UNKNOWN is retryable`() {
		with(ErrorCategory.Companion) { assertThat(ErrorCategory.UNKNOWN.isRetryable()).isEqualTo(true) }
	}

	@Test fun `AUTH_ERROR is not retryable`() {
		with(ErrorCategory.Companion) { assertThat(ErrorCategory.AUTH_ERROR.isRetryable()).isEqualTo(false) }
	}

	@Test fun `STORAGE_ERROR is not retryable`() {
		with(ErrorCategory.Companion) { assertThat(ErrorCategory.STORAGE_ERROR.isRetryable()).isEqualTo(false) }
	}

	@Test fun `SSL_ERROR is not retryable`() {
		with(ErrorCategory.Companion) { assertThat(ErrorCategory.SSL_ERROR.isRetryable()).isEqualTo(false) }
	}

	@Test fun `REPOSITORY_ERROR is not retryable`() {
		with(ErrorCategory.Companion) { assertThat(ErrorCategory.REPOSITORY_ERROR.isRetryable()).isEqualTo(false) }
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
