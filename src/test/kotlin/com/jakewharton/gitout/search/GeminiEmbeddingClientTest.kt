package com.jakewharton.gitout.search

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.jakewharton.gitout.Logger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

internal class GeminiEmbeddingClientTest {

    private val server = MockWebServer()
    private val logger = Logger(quiet = true, level = 0)

    // Captures the URL of the last intercepted request before URL rewriting
    private var capturedRequestUrl: String? = null

    @Before
    fun setUp() {
        server.start()
        capturedRequestUrl = null
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildClientForServer(): GeminiEmbeddingClient {
        // The GeminiEmbeddingClient hardcodes the Gemini URL; use an interceptor
        // to capture the original request URL, then rewrite to MockWebServer.
        val interceptingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                capturedRequestUrl = original.url.toString()
                val serverUrl = server.url("/v1beta/models/gemini-embedding-exp-03-07:embedContent")
                val newRequest = original.newBuilder()
                    .url(serverUrl)
                    .build()
                chain.proceed(newRequest)
            }
            .build()
        return GeminiEmbeddingClient(
            client = interceptingClient,
            apiKey = "test-api-key",
            logger = logger,
        )
    }

    @Test
    fun `embed success returns correct FloatArray and sends correct request`() {
        val responseJson = """{"embedding": {"values": [0.123, -0.456, 0.789]}}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val client = buildClientForServer()
        val result = runBlocking { client.embed("hello world") }

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        // Verify the original URL contained the model name and API key
        assertThat(capturedRequestUrl!!.contains("gemini-embedding-exp-03-07")).isTrue()
        assertThat(capturedRequestUrl!!.contains("test-api-key")).isTrue()
        val body = recorded.body.readUtf8()
        assertThat(body.contains("gemini-embedding-exp-03-07")).isTrue()
        assertThat(body.contains("hello world")).isTrue()

        assertThat(result.size).isEqualTo(3)
        assertThat(result[0]).isEqualTo(0.123f)
        assertThat(result[1]).isEqualTo(-0.456f)
        assertThat(result[2]).isEqualTo(0.789f)
    }

    @Test
    fun `embed truncates text to 8000 chars`() {
        val responseJson = """{"embedding": {"values": [0.1, 0.2]}}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val longText = "a".repeat(10000)
        val client = buildClientForServer()
        runBlocking { client.embed(longText) }

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        // The body contains the "text" field — verify it has 8000 a's but not 10000
        assertThat(body.contains("a".repeat(8000))).isTrue()
        assertThat(body.contains("a".repeat(8001))).isEqualTo(false)
    }

    @Test(expected = SearchException::class)
    fun `embed throws SearchException on HTTP 401`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": {"code": 401, "message": "API key not valid"}}"""))

        runBlocking { buildClientForServer().embed("test text") }
    }

    @Test(expected = SearchException::class)
    fun `embed throws SearchException on HTTP 429`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error": {"code": 429, "message": "Resource exhausted"}}"""))

        runBlocking { buildClientForServer().embed("test text") }
    }

    @Test(expected = SearchException::class)
    fun `embed throws SearchException on HTTP 500`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        runBlocking { buildClientForServer().embed("test text") }
    }

    @Test(expected = SearchException::class)
    fun `embed throws SearchException on malformed JSON response`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not valid json at all {{{"))

        runBlocking { buildClientForServer().embed("test text") }
    }
}
