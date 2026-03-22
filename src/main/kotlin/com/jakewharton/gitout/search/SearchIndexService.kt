package com.jakewharton.gitout.search

import com.jakewharton.gitout.Config
import com.jakewharton.gitout.Logger
import com.jakewharton.gitout.RepositoryMetadata
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal class SearchIndexService(
    private val geminiClient: GeminiEmbeddingClient,
    private val qdrantClient: QdrantClient,
    private val readmeExtractor: ReadmeExtractor,
    private val config: Config.Search,
    private val logger: Logger,
) {

    suspend fun indexRepositories(repos: Map<String, RepositoryMetadata>, backupDir: Path) {
        try {
            withContext(Dispatchers.IO) {
                qdrantClient.ensureCollection(config.collectionName, 3072)
            }
        } catch (e: Exception) {
            logger.warn("Failed to ensure Qdrant collection '${config.collectionName}': ${e.message}")
            return
        }

        for (repo in repos.values) {
            try {
                indexSingleRepo(repo, backupDir)
            } catch (e: Exception) {
                logger.warn("Unexpected error indexing '${repo.name}': ${e.message}")
            }
        }
    }

    private suspend fun indexSingleRepo(repo: RepositoryMetadata, backupDir: Path) {
        val bareRepoPath = backupDir.resolve("${repo.name}.git")
        val readmeContent = withContext(Dispatchers.IO) {
            readmeExtractor.extract(bareRepoPath)
        }
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

        val sha = MessageDigest.getInstance("SHA-256")
            .digest(documentText.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val pointId = UUID.nameUUIDFromBytes(("gitout-repo:${repo.name}").toByteArray()).toString()

        val existingPayload = withContext(Dispatchers.IO) {
            qdrantClient.getPayload(config.collectionName, pointId)
        }
        val existingSha = (existingPayload?.get("content_sha") as? JsonPrimitive)?.content
        if (existingSha == sha) {
            logger.trace { "Skipping '${repo.name}' — content unchanged (sha=$sha)" }
            return
        }

        val embedding = try {
            geminiClient.embed(documentText)
        } catch (e: SearchException) {
            logger.warn("Failed to embed '${repo.name}': ${e.message}")
            return
        }

        delay(100)

        val payload: Map<String, JsonElement> = mapOf(
            "name" to JsonPrimitive(repo.name),
            "description" to JsonPrimitive(repo.description ?: ""),
            "language" to JsonPrimitive(repo.language ?: "unknown"),
            "topics" to JsonArray(repo.topics.map { JsonPrimitive(it) }),
            "url" to JsonPrimitive("https://github.com/${repo.name}"),
            "content_sha" to JsonPrimitive(sha),
            "indexed_at" to JsonPrimitive(Clock.System.now().toString()),
        )

        val point = QdrantPoint(id = pointId, vector = embedding.toList(), payload = payload)

        try {
            withContext(Dispatchers.IO) {
                qdrantClient.upsert(config.collectionName, listOf(point))
            }
        } catch (e: SearchException) {
            logger.warn("Failed to upsert '${repo.name}' into Qdrant: ${e.message}")
            return
        }

        logger.info { "Indexed ${repo.name}" }
    }

    suspend fun search(query: String): List<SearchResult> {
        val embedding = try {
            geminiClient.embed(query)
        } catch (e: SearchException) {
            logger.warn("Failed to embed search query: ${e.message}")
            return emptyList()
        }

        return try {
            withContext(Dispatchers.IO) {
                qdrantClient.search(config.collectionName, embedding, config.topK)
            }
        } catch (e: SearchException) {
            logger.warn("Failed to search Qdrant: ${e.message}")
            emptyList()
        }
    }
}
