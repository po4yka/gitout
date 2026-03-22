package com.jakewharton.gitout.search

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.jakewharton.gitout.Config
import com.jakewharton.gitout.Logger
import com.jakewharton.gitout.RepositoryMetadata
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * Tests for SearchIndexService using two MockWebServer instances — one for Qdrant, one for Gemini.
 */
internal class SearchIndexServiceTest {

    private val qdrantServer = MockWebServer()
    private val geminiServer = MockWebServer()
    private val logger = Logger(quiet = true, level = 0)
    private val json = Json { ignoreUnknownKeys = true }
    private val backupDir = Files.createTempDirectory("gitout-test")

    private lateinit var qdrantClient: QdrantClient
    private lateinit var geminiClient: GeminiEmbeddingClient
    private lateinit var readmeExtractor: ReadmeExtractor
    private lateinit var service: SearchIndexService
    private lateinit var config: Config.Search

    @Before
    fun setUp() {
        qdrantServer.start()
        geminiServer.start()

        qdrantClient = QdrantClient(
            baseUrl = qdrantServer.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
            json = json,
        )

        // Wire GeminiEmbeddingClient to use the geminiServer via interceptor
        val geminiHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val rewritten = original.newBuilder()
                    .url(geminiServer.url("/v1beta/models/gemini-embedding-exp-03-07:embedContent"))
                    .build()
                chain.proceed(rewritten)
            }
            .build()

        geminiClient = GeminiEmbeddingClient(
            client = geminiHttpClient,
            apiKey = "test-key",
            logger = logger,
        )

        readmeExtractor = ReadmeExtractor(logger)

        config = Config.Search(
            enabled = true,
            qdrantUrl = qdrantServer.url("/").toString().trimEnd('/'),
            collectionName = "test-repos",
            topK = 5,
            autoIndex = false,
        )

        service = SearchIndexService(
            geminiClient = geminiClient,
            qdrantClient = qdrantClient,
            readmeExtractor = readmeExtractor,
            config = config,
            logger = logger,
        )
    }

    @After
    fun tearDown() {
        qdrantServer.shutdown()
        geminiServer.shutdown()
        backupDir.toFile().deleteRecursively()
    }

    private fun makeRepo(name: String): RepositoryMetadata = RepositoryMetadata(
        name = name,
        isArchived = false,
        isPrivate = false,
        isFork = false,
        visibility = "public",
        description = "A test repo",
        updatedAt = null,
        repoType = "owned",
        diskUsageKb = null,
        defaultBranch = "main",
        topics = listOf("kotlin", "test"),
        language = "Kotlin",
    )

    private fun embedResponse(vararg values: Float): String {
        val valuesJson = values.joinToString(", ")
        return """{"embedding": {"values": [$valuesJson]}}"""
    }

    private fun qdrantOkResponse(): MockResponse =
        MockResponse().setResponseCode(200).setBody("""{"result": true, "status": "ok", "time": 0.001}""")

    private fun qdrantNotFound(): MockResponse =
        MockResponse().setResponseCode(404).setBody("""{"status": "error"}""")

    private fun qdrantPayloadResponse(sha: String): MockResponse {
        val body = """
            {
              "result": {
                "id": "some-point",
                "payload": {"content_sha": "$sha"},
                "vector": null
              },
              "status": "ok",
              "time": 0.001
            }
        """.trimIndent()
        return MockResponse().setResponseCode(200).setBody(body)
    }

    private fun qdrantSearchResponse(): MockResponse {
        val body = """
            {
              "result": [
                {"id": "point-1", "score": 0.95, "payload": {"name": "myrepo"}}
              ],
              "status": "ok",
              "time": 0.002
            }
        """.trimIndent()
        return MockResponse().setResponseCode(200).setBody(body)
    }

    @Test
    fun `index success - full happy path calls ensureCollection, embed and upsert`() = runBlocking {
        // ensureCollection
        qdrantServer.enqueue(qdrantOkResponse())
        // getPayload returns 404 (not yet indexed)
        qdrantServer.enqueue(qdrantNotFound())
        // embed
        geminiServer.enqueue(MockResponse().setResponseCode(200).setBody(embedResponse(0.1f, 0.2f, 0.3f)))
        // upsert
        qdrantServer.enqueue(qdrantOkResponse())

        val repos = mapOf("user/myrepo" to makeRepo("user/myrepo"))
        service.indexRepositories(repos, backupDir)

        // Verify ensureCollection was called
        val ensureReq = qdrantServer.takeRequest()
        assertThat(ensureReq.method).isEqualTo("PUT")
        assertThat(ensureReq.path!!.contains("test-repos")).isTrue()

        // Verify getPayload was called
        val getPayloadReq = qdrantServer.takeRequest()
        assertThat(getPayloadReq.method).isEqualTo("GET")

        // Verify embed was called
        val embedReq = geminiServer.takeRequest()
        assertThat(embedReq.method).isEqualTo("POST")

        // Verify upsert was called
        val upsertReq = qdrantServer.takeRequest()
        assertThat(upsertReq.method).isEqualTo("PUT")
        assertThat(upsertReq.path!!.contains("test-repos")).isTrue()
        val upsertBody = upsertReq.body.readUtf8()
        assertThat(upsertBody.contains("user/myrepo")).isTrue()
    }

    @Test
    fun `SHA dedup - second index skips embedding when content_sha matches`() = runBlocking {
        val repo = makeRepo("user/deduprepo")

        // Compute what the sha will be (we need to enqueue a payload with the matching sha).
        // We index once first, then index again with the same sha returned from Qdrant.

        // First call: ensureCollection + getPayload(404) + embed + upsert
        qdrantServer.enqueue(qdrantOkResponse())           // ensureCollection
        qdrantServer.enqueue(qdrantNotFound())             // getPayload -> not found
        geminiServer.enqueue(MockResponse().setResponseCode(200).setBody(embedResponse(0.5f, 0.6f)))
        qdrantServer.enqueue(qdrantOkResponse())           // upsert

        val repos = mapOf("user/deduprepo" to repo)
        service.indexRepositories(repos, backupDir)

        // Drain the first pass requests
        qdrantServer.takeRequest()  // ensureCollection
        qdrantServer.takeRequest()  // getPayload
        geminiServer.takeRequest()  // embed
        qdrantServer.takeRequest()  // upsert

        // Now compute the actual document text + SHA for this repo to enqueue the right payload
        val readmeContent = "" // no actual git repo, ReadmeExtractor returns ""
        val documentText = buildString {
            append(repo.name)
            append("\n")
            append(repo.description ?: "")
            append("\n")
            append("topics: ")
            append(repo.topics.joinToString(", "))
            append("\n")
            append("language: ")
            append(repo.language ?: "unknown")
            append("\n---\n")
            append(readmeContent)
        }
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(documentText.toByteArray())
            .joinToString("") { "%02x".format(it) }

        // Second index: ensureCollection + getPayload(matching sha) -> should skip embed
        qdrantServer.enqueue(qdrantOkResponse())              // ensureCollection
        qdrantServer.enqueue(qdrantPayloadResponse(sha))       // getPayload returns matching sha

        service.indexRepositories(repos, backupDir)

        qdrantServer.takeRequest()  // ensureCollection
        qdrantServer.takeRequest()  // getPayload

        // Verify Gemini server received NO requests (embed was skipped)
        assertThat(geminiServer.requestCount).isEqualTo(1) // only the first pass
    }

    @Test
    fun `error isolation - embed failure on one repo does not prevent indexing the next`() = runBlocking {
        val repo1 = makeRepo("user/repo-fail")
        val repo2 = makeRepo("user/repo-ok")

        // ensureCollection
        qdrantServer.enqueue(qdrantOkResponse())

        // repo1: getPayload 404, embed FAILS
        qdrantServer.enqueue(qdrantNotFound())
        geminiServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        // repo2: getPayload 404, embed success, upsert success
        qdrantServer.enqueue(qdrantNotFound())
        geminiServer.enqueue(MockResponse().setResponseCode(200).setBody(embedResponse(0.1f, 0.2f)))
        qdrantServer.enqueue(qdrantOkResponse())

        // Use a LinkedHashMap to guarantee order
        val repos = linkedMapOf("user/repo-fail" to repo1, "user/repo-ok" to repo2)
        service.indexRepositories(repos, backupDir)

        // Drain Qdrant: ensureCollection + 2x getPayload + 1x upsert
        qdrantServer.takeRequest()  // ensureCollection
        qdrantServer.takeRequest()  // getPayload for repo1
        qdrantServer.takeRequest()  // getPayload for repo2
        qdrantServer.takeRequest()  // upsert for repo2

        // Verify embed was called for both repos (first failed, second succeeded)
        assertThat(geminiServer.requestCount).isEqualTo(2)
    }

    @Test
    fun `search success - embed and search results are returned`() = runBlocking {
        geminiServer.enqueue(MockResponse().setResponseCode(200).setBody(embedResponse(0.1f, 0.2f, 0.3f)))
        qdrantServer.enqueue(qdrantSearchResponse())

        val results = service.search("kotlin libraries")

        assertThat(results.size).isEqualTo(1)
        assertThat(results[0].id).isEqualTo("point-1")
        assertThat(results[0].score).isEqualTo(0.95f)

        val embedReq = geminiServer.takeRequest()
        assertThat(embedReq.method).isEqualTo("POST")

        val searchReq = qdrantServer.takeRequest()
        assertThat(searchReq.method).isEqualTo("POST")
        assertThat(searchReq.path!!.contains("test-repos/points/search")).isTrue()
    }

    @Test
    fun `search returns empty list when embed throws SearchException`() = runBlocking {
        geminiServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val results = service.search("some query")

        assertThat(results).isEmpty()
        // Qdrant search endpoint should NOT have been called
        assertThat(qdrantServer.requestCount).isEqualTo(0)
    }
}
