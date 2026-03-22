package com.jakewharton.gitout

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.counted
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.FileSystem

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
        throw UsageError("'index' command not yet implemented")
    }
}
