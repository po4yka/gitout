package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isEmpty
import org.junit.Test

class ConfigTest {
	@Test fun empty() {
		val config = """
			|version = 0
			""".trimMargin()
		val expected = Config(version = 0)
		assertThat(Config.parse(config)).isEqualTo(expected)
	}

	@Test fun full() {
		val config = """
			|version = 0
			|
			|[github]
			|user = "user"
			|token = "token_value"
			|
			|[github.archive]
			|owned = false
			|#repos = [
			|#	"example/one"
			|#]
			|
			|[github.clone]
			|starred = true
			|watched = true
			|gists = false
			|repos = [
			|  "example/two",
			|]
			|ignore = [
			|  "hey/there",
			|]
			|
			|[git.repos]
			|example = "https://example.com/example.git"
			""".trimMargin()
		val expected = Config(
			version = 0,
			github = Config.GitHub(
				user = "user",
				token = "token_value",
				archive = Config.GitHub.Archive(
					owned = false,
				),
				clone = Config.GitHub.Clone(
					starred = true,
					watched = true,
					gists = false,
					repos = listOf(
						"example/two",
					),
					ignore = listOf(
						"hey/there",
					),
				),
			),
			git = Config.Git(
				repos = mapOf(
					"example" to "https://example.com/example.git",
				),
			),
		)
		assertThat(Config.parse(config)).isEqualTo(expected)
	}

	@Test fun sslWithCertFile() {
		val config = """
			|version = 0
			|
			|[ssl]
			|cert_file = "/etc/ssl/certs/ca-certificates.crt"
			|verify_certificates = true
			""".trimMargin()
		val expected = Config(
			version = 0,
			ssl = Config.Ssl(
				certFile = "/etc/ssl/certs/ca-certificates.crt",
				verifyCertificates = true,
			),
		)
		assertThat(Config.parse(config)).isEqualTo(expected)
	}

	@Test fun sslDisableVerification() {
		val config = """
			|version = 0
			|
			|[ssl]
			|verify_certificates = false
			""".trimMargin()
		val expected = Config(
			version = 0,
			ssl = Config.Ssl(
				certFile = null,
				verifyCertificates = false,
			),
		)
		assertThat(Config.parse(config)).isEqualTo(expected)
	}

	@Test fun sslDefaults() {
		val config = """
			|version = 0
			|
			|[ssl]
			""".trimMargin()
		val expected = Config(
			version = 0,
			ssl = Config.Ssl(),
		)
		assertThat(Config.parse(config)).isEqualTo(expected)
	}

	@Test fun githubWithoutToken() {
		val config = """
			|version = 0
			|
			|[github]
			|user = "example"
			""".trimMargin()
		val expected = Config(
			version = 0,
			github = Config.GitHub(
				user = "example",
				token = null,
			),
		)
		assertThat(Config.parse(config)).isEqualTo(expected)
	}

	@Test fun githubWithToken() {
		val config = """
			|version = 0
			|
			|[github]
			|user = "example"
			|token = "ghp_test123"
			""".trimMargin()
		val expected = Config(
			version = 0,
			github = Config.GitHub(
				user = "example",
				token = "ghp_test123",
			),
		)
		assertThat(Config.parse(config)).isEqualTo(expected)
	}

	@Test fun unknownGithubCloneOptionIgnored() {
		val config = """
			|version = 0
			|
			|[github]
			|user = "example"
			|
			|[github.clone]
			|starred = true
			|owned = true
			""".trimMargin()
		val expected = Config(
			version = 0,
			github = Config.GitHub(
				user = "example",
				clone = Config.GitHub.Clone(
					starred = true,
				),
			),
		)
		assertThat(Config.parse(config)).isEqualTo(expected)
	}

	@Test fun telegramWithoutTokenUsesEnv() {
                val config = Config(
                        version = 1,
                        telegram = Config.Telegram(
                                token = null,
                                chatId = "123456",
                                enabled = true,
                        ),
		)
		assertThat(config.validate()).isEmpty()
	}

	@Test fun searchBlockParsesAllFields() {
		val config = """
			|version = 1
			|
			|[search]
			|enabled = true
			|qdrant_url = "http://qdrant:6333"
			|collection_name = "my_repos"
			|top_k = 5
			|auto_index = false
			""".trimMargin()
		val expected = Config(
			version = 1,
			search = Config.Search(
				enabled = true,
				qdrantUrl = "http://qdrant:6333",
				collectionName = "my_repos",
				topK = 5,
				autoIndex = false,
			),
		)
		assertThat(Config.parse(config)).isEqualTo(expected)
	}

	@Test fun searchDefaultsWhenBlockOmitted() {
		val config = """
			|version = 1
			""".trimMargin()
		val parsed = Config.parse(config)
		assertThat(parsed.search).isEqualTo(Config.Search())
	}

	@Test fun searchTopKBelowRangeProducesInvalidTopK() {
		val config = Config(version = 1, search = Config.Search(topK = 0))
		val errors = config.validate()
		assertThat(errors).contains(ValidationError.InvalidTopK(0))
	}

	@Test fun searchTopKAboveRangeProducesInvalidTopK() {
		val config = Config(version = 1, search = Config.Search(topK = 101))
		val errors = config.validate()
		assertThat(errors).contains(ValidationError.InvalidTopK(101))
	}

	@Test fun searchTopKAtBoundariesIsValid() {
		val configMin = Config(version = 1, search = Config.Search(topK = 1))
		val configMax = Config(version = 1, search = Config.Search(topK = 100))
		assertThat(configMin.validate().filterIsInstance<ValidationError.InvalidTopK>()).isEmpty()
		assertThat(configMax.validate().filterIsInstance<ValidationError.InvalidTopK>()).isEmpty()
	}

	@Test fun searchBlankQdrantUrlWhenEnabledProducesEmptyQdrantUrl() {
		val config = Config(version = 1, search = Config.Search(enabled = true, qdrantUrl = "   "))
		val errors = config.validate()
		assertThat(errors).contains(ValidationError.EmptyQdrantUrl)
	}

	@Test fun searchBlankCollectionNameWhenEnabledProducesEmptyCollectionName() {
		val config = Config(version = 1, search = Config.Search(enabled = true, collectionName = ""))
		val errors = config.validate()
		assertThat(errors).contains(ValidationError.EmptyCollectionName)
	}

	@Test fun searchBlankUrlAndCollectionNameNotValidatedWhenDisabled() {
		val config = Config(
			version = 1,
			search = Config.Search(enabled = false, qdrantUrl = "", collectionName = ""),
		)
		val errors = config.validate()
		assertThat(errors.filterIsInstance<ValidationError.EmptyQdrantUrl>()).isEmpty()
		assertThat(errors.filterIsInstance<ValidationError.EmptyCollectionName>()).isEmpty()
	}
}
