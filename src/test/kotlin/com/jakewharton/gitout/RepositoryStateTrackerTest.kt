package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class RepositoryStateTrackerTest {
	private val json = Json { ignoreUnknownKeys = true }

	@Test fun `RepositoryMetadata topics and language survive serialization round trip`() {
		val original = RepositoryMetadata(
			name = "kotlin-cli",
			isArchived = false,
			isPrivate = false,
			isFork = false,
			visibility = "PUBLIC",
			description = "A CLI tool written in Kotlin",
			updatedAt = "2024-01-01T00:00:00Z",
			repoType = "owned",
			diskUsageKb = 100L,
			defaultBranch = "main",
			topics = listOf("kotlin", "cli"),
			language = "Kotlin",
		)

		val serialized = json.encodeToString(original)
		val deserialized = json.decodeFromString<RepositoryMetadata>(serialized)

		assertThat(deserialized.topics).isEqualTo(listOf("kotlin", "cli"))
		assertThat(deserialized.language).isEqualTo("Kotlin")
	}

	@Test fun `RepositoryMetadata deserializes with default values when topics and language are absent`() {
		// Simulates an old JSON snapshot that pre-dates the topics and language fields
		val oldJson = """
			{
				"name": "old-repo",
				"isArchived": false,
				"isPrivate": true,
				"isFork": false,
				"visibility": "PRIVATE",
				"description": null,
				"updatedAt": null,
				"repoType": "owned"
			}
		""".trimIndent()

		val deserialized = json.decodeFromString<RepositoryMetadata>(oldJson)

		assertThat(deserialized.topics).isEqualTo(emptyList())
		assertThat(deserialized.language).isNull()
	}
}
