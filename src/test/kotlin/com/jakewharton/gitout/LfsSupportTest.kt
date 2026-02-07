package com.jakewharton.gitout

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

class LfsSupportTest {
	private fun createLogger() = Logger(quiet = true, level = 0)

	@Test fun isLfsRepoReturnsFalseForNonExistentPath() {
		val lfs = LfsSupport(createLogger(), 10.minutes)
		val nonExistent = Files.createTempDirectory("gitout-test-").resolve("nonexistent")
		assertThat(lfs.isLfsRepo(nonExistent)).isFalse()
	}

	@Test fun isLfsRepoReturnsFalseForEmptyDirectory() {
		val lfs = LfsSupport(createLogger(), 10.minutes)
		val tempDir = Files.createTempDirectory("gitout-test-")
		try {
			assertThat(lfs.isLfsRepo(tempDir)).isFalse()
		} finally {
			tempDir.toFile().deleteRecursively()
		}
	}

	@Test fun isLfsRepoDetectsLfsDirectory() {
		val lfs = LfsSupport(createLogger(), 10.minutes)
		val tempDir = Files.createTempDirectory("gitout-test-")
		try {
			Files.createDirectory(tempDir.resolve("lfs"))
			assertThat(lfs.isLfsRepo(tempDir)).isTrue()
		} finally {
			tempDir.toFile().deleteRecursively()
		}
	}

	@Test fun fetchLfsObjectsReturnsFalseForNonExistentPath() {
		val lfs = LfsSupport(createLogger(), 10.minutes)
		val nonExistent = Files.createTempDirectory("gitout-test-").resolve("nonexistent")
		assertThat(lfs.fetchLfsObjects(nonExistent)).isFalse()
	}

	@Test fun syncLfsIfNeededReturnsTrueForNonLfsRepo() {
		val lfs = LfsSupport(createLogger(), 10.minutes)
		val tempDir = Files.createTempDirectory("gitout-test-")
		try {
			// No LFS markers, should return true (nothing to do)
			assertThat(lfs.syncLfsIfNeeded(tempDir)).isTrue()
		} finally {
			tempDir.toFile().deleteRecursively()
		}
	}
}
