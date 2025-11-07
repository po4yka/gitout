package com.jakewharton.gitout

import assertk.assertThat
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
}
