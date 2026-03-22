package com.jakewharton.gitout

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Resolves the Gemini API key from environment variables with the following priority:
 * 1. GEMINI_API_KEY environment variable (key value directly)
 * 2. GEMINI_API_KEY_FILE environment variable (path to file containing key)
 */
internal fun resolveGeminiApiKey(logger: Logger): String? {
	val envKey = System.getenv("GEMINI_API_KEY")
	if (!envKey.isNullOrBlank()) {
		logger.debug { "Using Gemini API key from GEMINI_API_KEY environment variable" }
		return envKey
	}
	val keyFile = System.getenv("GEMINI_API_KEY_FILE")
	if (!keyFile.isNullOrBlank()) {
		val path = Path(keyFile)
		if (path.exists()) {
			val key = path.readText().trim()
			if (key.isNotBlank()) {
				logger.debug { "Using Gemini API key from file: $keyFile" }
				return key
			}
		}
		logger.warn("GEMINI_API_KEY_FILE points to missing or empty file: $keyFile")
	}
	return null
}
