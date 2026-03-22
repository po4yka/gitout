package com.jakewharton.gitout

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.counted
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.jakewharton.gitout.search.GeminiEmbeddingClient
import com.jakewharton.gitout.search.QdrantClient
import com.jakewharton.gitout.search.ReadmeExtractor
import com.jakewharton.gitout.search.SearchIndexService
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

internal class IndexCommand(fs: FileSystem) : SuspendingCliktCommand(name = "index") {
    override fun help(context: Context): String = "Index all repositories for semantic search"

    private val config by argument()
        .path(mustExist = true, canBeDir = false, fileSystem = fs)
        .help("Configuration TOML")

    private val destination by argument()
        .path(mustExist = true, canBeFile = false, fileSystem = fs)
        .help("Backup directory")

    private val verbosity by option("--verbose", "-v")
        .counted(limit = 3)
        .help("Increase logging verbosity")

    private val quiet by option("--quiet", "-q")
        .flag()
        .help("Suppress output")

    override suspend fun run() {
        val logger = Logger(quiet, verbosity)

        val client = OkHttpClient.Builder().build()

        val parsedConfig = Config.parse(config.readText(), logger)

        if (!parsedConfig.search.enabled) {
            echo("Search is not enabled in config. Set search.enabled = true")
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            return
        }

        val geminiApiKey = resolveGeminiApiKey(logger)
        if (geminiApiKey == null) {
            echo("GEMINI_API_KEY or GEMINI_API_KEY_FILE is not set")
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }

        val geminiClient = GeminiEmbeddingClient(client, geminiApiKey, logger)
        val qdrantClient = QdrantClient(parsedConfig.search.qdrantUrl, client, Json)
        val readmeExtractor = ReadmeExtractor(logger)
        val searchIndexService = SearchIndexService(geminiClient, qdrantClient, readmeExtractor, parsedConfig.search, logger)

        val stateFile = destination.resolve("github").resolve(".gitout-state.json")
        if (!stateFile.exists()) {
            echo("No repository state found at $stateFile. Run gitout sync first.")
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            return
        }

        val json = Json { ignoreUnknownKeys = true }
        val state = json.decodeFromString<RepositoryState>(stateFile.readText())
        val repos = state.repositories

        logger.lifecycle { "Starting indexing of ${repos.size} repositories..." }
        val cloneDir = destination.resolve("github").resolve("clone")
        searchIndexService.indexRepositories(repos, cloneDir)
        logger.lifecycle { "Indexing complete." }

        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
