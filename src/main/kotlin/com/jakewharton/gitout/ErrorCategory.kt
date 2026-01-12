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
	 * - "Repository not found" (could be private)
	 */
	AUTH_ERROR,

	/**
	 * Repository-specific issues - may need special handling.
	 * Examples:
	 * - "Repository is empty"
	 * - "Remote HEAD refers to nonexistent ref"
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

			// Timeout errors
			if (lowerMessage.contains("timeout") ||
				lowerMessage.contains("timed out")) {
				return TIMEOUT
			}

			// Network errors
			if (lowerMessage.contains("connection reset") ||
				lowerMessage.contains("connection refused") ||
				lowerMessage.contains("connection timed out") ||
				lowerMessage.contains("network is unreachable") ||
				lowerMessage.contains("host is unreachable") ||
				lowerMessage.contains("recv failure") ||
				lowerMessage.contains("couldn't connect") ||
				lowerMessage.contains("name or service not known") ||
				lowerMessage.contains("temporary failure in name resolution")) {
				return NETWORK_ERROR
			}

			// Authentication errors
			if (lowerMessage.contains("authentication failed") ||
				lowerMessage.contains("permission denied") ||
				lowerMessage.contains("access denied") ||
				lowerMessage.contains("invalid credentials") ||
				lowerMessage.contains("bad credentials") ||
				(lowerMessage.contains("repository not found") && !lowerMessage.contains("clone"))) {
				return AUTH_ERROR
			}

			// SSL/TLS errors
			if (lowerMessage.contains("ssl certificate") ||
				lowerMessage.contains("certificate problem") ||
				lowerMessage.contains("certificate verify") ||
				lowerMessage.contains("local issuer certificate") ||
				lowerMessage.contains("ssl_error") ||
				lowerMessage.contains("tls")) {
				return SSL_ERROR
			}

			// Storage errors
			if (lowerMessage.contains("no space left") ||
				lowerMessage.contains("disk quota") ||
				lowerMessage.contains("cannot allocate") ||
				lowerMessage.contains("out of memory")) {
				return STORAGE_ERROR
			}

			// Repository errors
			if (lowerMessage.contains("repository is empty") ||
				lowerMessage.contains("remote head") ||
				lowerMessage.contains("nonexistent ref") ||
				lowerMessage.contains("invalid ref")) {
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
			else -> 1.0
		}
	}
}
