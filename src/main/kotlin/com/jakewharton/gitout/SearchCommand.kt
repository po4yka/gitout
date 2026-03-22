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
import java.nio.file.FileSystem

internal class SearchCommand(fs: FileSystem) : SuspendingCliktCommand(name = "search") {
    override fun help(context: Context): String = "Search repositories by natural language query"

    private val query by argument().help("Natural language search query")

    private val config by argument()
        .path(mustExist = true, canBeDir = false, fileSystem = fs)
        .help("Configuration TOML")

    private val destination by argument()
        .path(mustExist = true, canBeFile = false, fileSystem = fs)
        .help("Backup directory")

    @Suppress("unused")
    private val verbosity by option("--verbose", "-v")
        .counted(limit = 3)
        .help("Increase logging verbosity")

    @Suppress("unused")
    private val quiet by option("--quiet", "-q")
        .flag()
        .help("Suppress output")

    override suspend fun run() {
        echo("Search not yet implemented. Query: $query")
    }
}
