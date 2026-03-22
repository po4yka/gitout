package com.jakewharton.gitout.search

import com.jakewharton.gitout.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val EMBED_TEXT_MAX_CHARS = 8000
private const val GEMINI_MODEL = "gemini-embedding-exp-03-07"
private const val GEMINI_EMBED_URL =
    "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:embedContent"
private val APPLICATION_JSON = "application/json".toMediaType()

internal class GeminiEmbeddingClient(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val logger: Logger,
) {
    private val json = Json { ignoreUnknownKeys = true }

    internal suspend fun embed(text: String): FloatArray {
        val truncated = text.take(EMBED_TEXT_MAX_CHARS)
        val logMsg = if (truncated.length < text.length)
            "Embedding ${text.length} chars (truncated to ${truncated.length})"
        else
            "Embedding ${truncated.length} chars"
        logger.debug { logMsg }

        val body = buildJsonObject {
            put("model", "models/$GEMINI_MODEL")
            putJsonObject("content") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", truncated) })
                }
            }
        }
        val requestBody = json.encodeToString(JsonObject.serializer(), body)
            .toRequestBody(APPLICATION_JSON)

        val request = Request.Builder()
            .url("$GEMINI_EMBED_URL?key=$apiKey")
            .post(requestBody)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()
                    if (!response.isSuccessful) {
                        val snippet = responseBody.take(200)
                        throw SearchException("Gemini embedding failed: HTTP ${response.code} $snippet")
                    }
                    try {
                        val root = json.parseToJsonElement(responseBody).jsonObject
                        val values = root["embedding"]?.jsonObject?.get("values")?.jsonArray
                            ?: throw SearchException("Missing 'embedding.values' in Gemini response")
                        FloatArray(values.size) { i -> values[i].jsonPrimitive.content.toFloat() }
                    } catch (e: SearchException) {
                        throw e
                    } catch (e: Exception) {
                        throw SearchException("Failed to parse Gemini embedding response: ${e.message}", e)
                    }
                }
            } catch (e: SearchException) {
                throw e
            } catch (e: Exception) {
                throw SearchException("Network error during Gemini embedding: ${e.message}", e)
            }
        }
    }
}
