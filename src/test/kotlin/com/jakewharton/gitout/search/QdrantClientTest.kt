package com.jakewharton.gitout.search

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

internal class QdrantClientTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var qdrantClient: QdrantClient

    @Before
    fun setUp() {
        server.start()
        qdrantClient = QdrantClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
            json = json,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `ensureCollection sends correct PUT request`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"result": true, "status": "ok", "time": 0.001}"""))

        qdrantClient.ensureCollection("repos", 768)

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("PUT")
        assertThat(recorded.path).isEqualTo("/collections/repos")
        val body = recorded.body.readUtf8()
        assertThat(body.contains("\"size\":768")).isTrue()
        assertThat(body.contains("\"distance\":\"Cosine\"")).isTrue()
    }

    @Test
    fun `ensureCollection with 409 response does NOT throw`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"status": {"error": "already exists"}}"""))

        // Should not throw
        qdrantClient.ensureCollection("repos", 768)

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("PUT")
    }

    @Test(expected = SearchException::class)
    fun `ensureCollection throws SearchException on 500`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        qdrantClient.ensureCollection("repos", 768)
    }

    @Test
    fun `upsert sends correct PUT request`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"result": {"operation_id": 1, "status": "completed"}, "status": "ok", "time": 0.01}"""))

        val points = listOf(
            QdrantPoint(
                id = "abc-123",
                vector = listOf(0.1f, 0.2f, 0.3f),
                payload = mapOf("name" to JsonPrimitive("myrepo"), "url" to JsonPrimitive("https://github.com/user/myrepo")),
            )
        )
        qdrantClient.upsert("repos", points)

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("PUT")
        assertThat(recorded.path).isEqualTo("/collections/repos/points")
        val body = recorded.body.readUtf8()
        assertThat(body.contains("\"points\"")).isTrue()
        assertThat(body.contains("abc-123")).isTrue()
        assertThat(body.contains("myrepo")).isTrue()
    }

    @Test(expected = SearchException::class)
    fun `upsert throws SearchException on failure`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"status": "error"}"""))

        qdrantClient.upsert("repos", emptyList())
    }

    @Test
    fun `search sends correct POST and parses response`() {
        val responseJson = """
            {
              "result": [
                {
                  "id": "point-1",
                  "score": 0.91,
                  "payload": {"name": "myrepo", "url": "https://github.com/user/myrepo"}
                },
                {
                  "id": "point-2",
                  "score": 0.85,
                  "payload": {"name": "otherrepo", "url": "https://github.com/user/otherrepo"}
                }
              ],
              "status": "ok",
              "time": 0.002
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val vector = floatArrayOf(0.1f, 0.2f, 0.3f)
        val results = qdrantClient.search("repos", vector, topK = 5)

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/collections/repos/points/search")
        val body = recorded.body.readUtf8()
        assertThat(body.contains("\"limit\":5")).isTrue()
        assertThat(body.contains("\"with_payload\":true")).isTrue()

        assertThat(results.size).isEqualTo(2)
        assertThat(results[0].id).isEqualTo("point-1")
        assertThat(results[0].score).isEqualTo(0.91f)
        assertThat(results[0].payload["name"]).isEqualTo(JsonPrimitive("myrepo"))
        assertThat(results[1].id).isEqualTo("point-2")
        assertThat(results[1].score).isEqualTo(0.85f)
    }

    @Test(expected = SearchException::class)
    fun `search throws SearchException on failure`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"status": "error"}"""))

        qdrantClient.search("repos", floatArrayOf(0.1f), topK = 5)
    }

    @Test
    fun `getPayload returns null on 404`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"status": "error"}"""))

        val result = qdrantClient.getPayload("repos", "nonexistent-id")

        assertThat(result).isNull()
    }

    @Test
    fun `getPayload returns payload map on 200`() {
        val responseJson = """
            {
              "result": {
                "id": "point-1",
                "version": 0,
                "score": null,
                "payload": {
                  "name": "myrepo",
                  "url": "https://github.com/user/myrepo",
                  "content_sha": "abc123"
                },
                "vector": null
              },
              "status": "ok",
              "time": 0.001
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val payload = qdrantClient.getPayload("repos", "point-1")

        assertThat(payload).isNotNull()
        assertThat(payload!!["name"]).isEqualTo(JsonPrimitive("myrepo"))
        assertThat(payload["url"]).isEqualTo(JsonPrimitive("https://github.com/user/myrepo"))
        assertThat(payload["content_sha"]).isEqualTo(JsonPrimitive("abc123"))

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("GET")
        assertThat(recorded.path).isEqualTo("/collections/repos/points/point-1")
    }

    @Test(expected = SearchException::class)
    fun `getPayload throws SearchException on 500`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        qdrantClient.getPayload("repos", "point-1")
    }
}
