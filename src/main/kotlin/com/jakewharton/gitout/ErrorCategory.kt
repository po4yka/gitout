package com.jakewharton.gitout

/**
 * Categories of errors that can occur during git sync operations.
 * Used for intelligent retry strategies and error-specific handling.
 */
internal enum class ErrorCategory {
	/**
	 * HTTP/2 protocol errors - should retry with HTTP/1.1 fallback.
	 * Examples:
	 * - "curl 92 HTTP/2 stream 5 was not closed cleanly: CANCEL"
	 * - "curl 16 Error in the HTTP2 framing layer"
	 */
	HTTP2_ERROR,

	/**
	 * Network connectivity issues - should retry with longer delays.
	 * Examples:
	 * - "Connection reset by peer"
	 * - "Connection timed out"
	 * - "Network is unreachable"
	 */
	NETWORK_ERROR,

	/**
	 * Operation timed out - may need larger timeout or different strategy.
	 * Examples:
	 * - "timeout 1h"
	 * - Process exceeded timeout duration
	 */
	TIMEOUT,

	/**
	 * Authentication or authorization failure - should not retry.
	 * Examples:
	 * - "Authentication failed"
	 * - "Permission denied"
	 * - "403 Forbidden"
	 * - "Repository not found" (GitHub returns 404 for private repos with wrong auth)
	 */
	AUTH_ERROR,

	/**
	 * Repository-specific issues - may need special handling.
	 * Examples:
	 * - "Repository is empty"
	 * - "Remote HEAD refers to nonexistent ref"
	 * - "couldn't find remote ref refs/heads/main"
	 */
	REPOSITORY_ERROR,

	/**
	 * Disk space or filesystem errors - should not retry.
	 * Examples:
	 * - "No space left on device"
	 * - "Disk quota exceeded"
	 */
	STORAGE_ERROR,

	/**
	 * SSL/TLS certificate errors - may need config change.
	 * Examples:
	 * - "SSL certificate problem"
	 * - "unable to get local issuer certificate"
	 */
	SSL_ERROR,

	/**
	 * GitHub/remote API rate limiting - should retry with longer delay.
	 * Examples:
	 * - "rate limit exceeded"
	 * - "429 Too Many Requests"
	 */
	RATE_LIMIT,

	/**
	 * Unknown or unclassified errors - use default retry strategy.
	 */
	UNKNOWN;

	companion object {
		/**
		 * Classifies an error based on the exception message and exit code.
		 */
		fun classify(errorMessage: String?, exitCode: Int? = null): ErrorCategory {
			if (errorMessage == null) return UNKNOWN

			val lowerMessage = errorMessage.lowercase()

			// HTTP/2 errors - highest priority for detection
			if (lowerMessage.contains("http/2") ||
				lowerMessage.contains("http2") ||
				lowerMessage.contains("curl 92") ||
				lowerMessage.contains("curl 16") ||
				lowerMessage.contains("stream") && lowerMessage.contains("cancel")) {
				return HTTP2_ERROR
			}

			// Connection timeout is a network error - check before generic timeout
			if (lowerMessage.contains("connection timed out")) {
				return NETWORK_ERROR
			}

			// Timeout errors (generic process/operation timeout)
			if (lowerMessage.contains("timeout") ||
				lowerMessage.contains("timed out")) {
				return TIMEOUT
			}

			// Network errors
			if (lowerMessage.contains("connection reset") ||
				lowerMessage.contains("connection refused") ||
				lowerMessage.contains("network is unreachable") ||
				lowerMessage.contains("host is unreachable") ||
				lowerMessage.contains("recv failure") ||
				lowerMessage.contains("couldn't connect") ||
				lowerMessage.contains("name or service not known") ||
				lowerMessage.contains("temporary failure in name resolution") ||
				lowerMessage.contains("could not resolve host")) {
				return NETWORK_ERROR
			}

			// Rate limiting
			if (lowerMessage.contains("rate limit") ||
				lowerMessage.contains("too many requests") ||
				lowerMessage.contains("retry after") ||
				lowerMessage.contains("429")) {
				return RATE_LIMIT
			}

			// Authentication errors
			if (lowerMessage.contains("authentication failed") ||
				lowerMessage.contains("permission denied") ||
				lowerMessage.contains("access denied") ||
				lowerMessage.contains("invalid credentials") ||
				lowerMessage.contains("bad credentials") ||
				lowerMessage.contains("could not read username") ||
				lowerMessage.contains("terminal prompts disabled") ||
				lowerMessage.contains("403") ||
				lowerMessage.contains("repository not found")) {
				return AUTH_ERROR
			}

			// SSL/TLS errors - use specific patterns to avoid false positives from repo names
			if (lowerMessage.contains("ssl certificate") ||
				lowerMessage.contains("certificate problem") ||
				lowerMessage.contains("certificate verify") ||
				lowerMessage.contains("local issuer certificate") ||
				lowerMessage.contains("ssl_error") ||
				lowerMessage.contains("ssl: ") ||
				lowerMessage.contains("tlsv1") ||
				lowerMessage.contains("tls handshake") ||
				lowerMessage.contains("tls alert")) {
				return SSL_ERROR
			}

			// Storage errors
			if (lowerMessage.contains("no space left") ||
				lowerMessage.contains("disk quota") ||
				lowerMessage.contains("cannot allocate") ||
				lowerMessage.contains("out of memory")) {
				return STORAGE_ERROR
			}

			// Repository errors (non-retryable - requires manual intervention or config change)
			if (lowerMessage.contains("repository is empty") ||
				lowerMessage.contains("remote head") ||
				lowerMessage.contains("nonexistent ref") ||
				lowerMessage.contains("invalid ref") ||
				lowerMessage.contains("couldn't find remote ref") ||
				lowerMessage.contains("remote ref does not exist") ||
				lowerMessage.contains("bad object") ||
				lowerMessage.contains("is corrupt") ||
				lowerMessage.contains("does not appear to be a git") ||
				lowerMessage.contains("fatal: unable to access") ||
				lowerMessage.contains("404 not found")) {
				return REPOSITORY_ERROR
			}

			// Check for common git error patterns
			if (lowerMessage.contains("early eof") ||
				lowerMessage.contains("unexpected disconnect") ||
				lowerMessage.contains("fetch-pack")) {
				// These are often caused by HTTP/2 issues
				return HTTP2_ERROR
			}

			return UNKNOWN
		}

		/**
		 * Determines if this error category should use HTTP/1.1 fallback.
		 */
		fun ErrorCategory.shouldUseHttp1Fallback(): Boolean = when (this) {
			HTTP2_ERROR -> true
			NETWORK_ERROR -> true // Network errors might also benefit from HTTP/1.1
			else -> false
		}

		/**
		 * Determines if this error category should be retried at all.
		 */
		fun ErrorCategory.isRetryable(): Boolean = when (this) {
			HTTP2_ERROR -> true
			NETWORK_ERROR -> true
			TIMEOUT -> true
			RATE_LIMIT -> true
			UNKNOWN -> true
			AUTH_ERROR -> false
			STORAGE_ERROR -> false
			SSL_ERROR -> false // Usually requires config change
			REPOSITORY_ERROR -> false // Usually requires manual intervention
		}

		/**
		 * Suggests a delay multiplier for retries based on error category.
		 */
		fun ErrorCategory.delayMultiplier(): Double = when (this) {
			HTTP2_ERROR -> 1.0 // Normal delay, just need protocol change
			NETWORK_ERROR -> 2.0 // Longer delay for network issues
			TIMEOUT -> 1.5 // Slightly longer delay
			RATE_LIMIT -> 3.0 // Much longer delay to respect rate limits
			else -> 1.0
		}

		/**
		 * Human-readable display name for use in notifications.
		 */
		val ErrorCategory.displayName: String get() = when (this) {
			HTTP2_ERROR -> "HTTP/2 Error"
			NETWORK_ERROR -> "Network Error"
			TIMEOUT -> "Timeout"
			AUTH_ERROR -> "Authentication Error"
			REPOSITORY_ERROR -> "Git Error"
			STORAGE_ERROR -> "Disk Space Error"
			SSL_ERROR -> "SSL/TLS Error"
			RATE_LIMIT -> "Rate Limiting"
			UNKNOWN -> "Unknown Error"
		}

		/**
		 * Actionable recovery suggestion for each error category.
		 */
		val ErrorCategory.suggestion: String get() = when (this) {
			HTTP2_ERROR -> "Retry with HTTP/1.1 fallback is automatic. If persistent, check network proxy settings."
			NETWORK_ERROR -> "Check your internet connection and DNS settings. Verify the repository URL is accessible."
			TIMEOUT -> "Operation timed out. The repository may be large or the connection slow. Consider increasing timeout."
			AUTH_ERROR -> "Verify your credentials and token permissions. Ensure the token hasn't expired."
			REPOSITORY_ERROR -> "Verify the repository exists and the URL is correct. Check if it has been deleted, moved, or emptied."
			STORAGE_ERROR -> "Free up disk space on your system. Consider archiving or removing old backups."
			SSL_ERROR -> "Check SSL certificate configuration. Verify system certificates are up to date or configure cert_file in config."
			RATE_LIMIT -> "Wait before retrying. Consider reducing sync frequency or using authentication to increase rate limits."
			UNKNOWN -> "Check the error message for details. Verify your configuration and network connectivity."
		}
	}
}
