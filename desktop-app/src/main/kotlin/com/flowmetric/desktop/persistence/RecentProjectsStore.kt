package com.flowmetric.desktop.persistence

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class RecentProjectsStore(
    private val storePath: Path = defaultStorePath(),
) {
    fun read(): List<String> {
        if (!storePath.exists()) return emptyList()
        return runCatching {
            storePath.bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotBlank() }.toList()
            }
        }.getOrDefault(emptyList())
    }

    fun add(path: String) {
        val normalized = path.trim()
        if (normalized.isBlank()) return

        val updated = listOf(normalized) + read().filterNot { it == normalized }
        write(updated.take(MAX_RECENT_PROJECTS))
    }

    fun remove(path: String) {
        val normalized = path.trim()
        if (normalized.isBlank()) return
        write(read().filterNot { it == normalized })
    }

    private fun write(paths: List<String>) {
        storePath.parent?.createDirectories()
        storePath.bufferedWriter().use { writer ->
            paths.forEach { writer.appendLine(it) }
        }
    }

    companion object {
        private const val MAX_RECENT_PROJECTS = 8

        private fun defaultStorePath(): Path {
            val userHome = System.getProperty("user.home")
            return Path.of(userHome, ".flowmetric-desktop", "recent-projects.txt")
        }
    }
}
