package com.jakewharton.gitout.search

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val APPLICATION_JSON = "application/json".toMediaType()

@Poko @Serializable
internal class QdrantPoint(
    val id: String,
    val vector: List<Float>,
    val payload: Map<String, JsonElement>,
)

@Poko @Serializable
internal class SearchResult(
    val id: String,
    val score: Float,
    val payload: Map<String, JsonElement>,
)

internal class QdrantClient(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val json: Json,
) {

    internal fun ensureCollection(name: String, vectorSize: Int) {
        val body = buildJsonObject {
            putJsonObject("vectors") {
                put("size", vectorSize)
                put("distance", "Cosine")
            }
        }
        val requestBody = json.encodeToString(JsonObject.serializer(), body).toRequestBody(APPLICATION_JSON)
        val request = Request.Builder()
            .url("$baseUrl/collections/$name")
            .put(requestBody)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 409) {
                // Collection already exists — treat as success
                return
            }
            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                throw SearchException("Failed to ensure collection '$name': HTTP ${response.code} $errorBody")
            }
        }
    }

    internal fun upsert(collectionName: String, points: List<QdrantPoint>) {
        val pointsJson = json.encodeToJsonElement(points)
        val body = buildJsonObject {
            put("points", pointsJson)
        }
        val requestBody = json.encodeToString(JsonObject.serializer(), body).toRequestBody(APPLICATION_JSON)
        val request = Request.Builder()
            .url("$baseUrl/collections/$collectionName/points")
            .put(requestBody)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                throw SearchException("Failed to upsert points into '$collectionName': HTTP ${response.code} $errorBody")
            }
        }
    }

    internal fun search(collectionName: String, vector: FloatArray, topK: Int): List<SearchResult> {
        val vectorJson = json.encodeToJsonElement(vector.toList())
        val body = buildJsonObject {
            put("vector", vectorJson)
            put("limit", topK)
            put("with_payload", true)
        }
        val requestBody = json.encodeToString(JsonObject.serializer(), body).toRequestBody(APPLICATION_JSON)
        val request = Request.Builder()
            .url("$baseUrl/collections/$collectionName/points/search")
            .post(requestBody)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                throw SearchException("Failed to search '$collectionName': HTTP ${response.code} $errorBody")
            }
            val responseBody = response.body.string()
            try {
                val root = json.parseToJsonElement(responseBody).jsonObject
                val resultArray = root["result"]?.jsonArray
                    ?: throw SearchException("Missing 'result' field in search response")
                return resultArray.map { element ->
                    val obj = element.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content
                        ?: throw SearchException("Missing 'id' in search result")
                    val score = obj["score"]?.jsonPrimitive?.content?.toFloat()
                        ?: throw SearchException("Missing 'score' in search result")
                    val payload = obj["payload"]?.jsonObject?.toMap() ?: emptyMap()
                    SearchResult(id = id, score = score, payload = payload)
                }
            } catch (e: SearchException) {
                throw e
            } catch (e: Exception) {
                throw SearchException("Failed to parse Qdrant response: ${e.message}", e)
            }
        }
    }

    internal fun getPayload(collectionName: String, pointId: String): Map<String, JsonElement>? {
        val request = Request.Builder()
            .url("$baseUrl/collections/$collectionName/points/$pointId")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) {
                return null
            }
            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                throw SearchException("Failed to get point '$pointId' from '$collectionName': HTTP ${response.code} $errorBody")
            }
            val responseBody = response.body.string()
            try {
                val root = json.parseToJsonElement(responseBody).jsonObject
                val result = root["result"]?.jsonObject
                    ?: throw SearchException("Missing 'result' field in getPayload response")
                val payloadObj = result["payload"]?.jsonObject ?: return emptyMap()
                return payloadObj.toMap()
            } catch (e: SearchException) {
                throw e
            } catch (e: Exception) {
                throw SearchException("Failed to parse Qdrant response: ${e.message}", e)
            }
        }
    }
}
