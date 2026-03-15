package com.flowmetric.desktop.logging

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * Represents one persisted error line from the desktop log file.
 *
 * The [id] is the original zero-based line index in the log file so the entry can be deleted
 * later without reparsing the file contents from the UI layer.
 */
data class DesktopErrorLogEntry(
    val id: Int,
    val line: String,
)

/**
 * Minimal file-backed logger for the desktop application.
 *
 * This logger writes plain-text entries to `~/.flowmetric-desktop/desktop.log` so developers and
 * users can inspect local application issues without any remote reporting.
 */
object FlowMetricDesktopLogger {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())
    private val logPath: Path by lazy {
        Path.of(System.getProperty("user.home"), ".flowmetric-desktop", "desktop.log").also {
            it.parent?.createDirectories()
            if (!Files.exists(it)) {
                it.writeText("")
            }
        }
    }

    /**
     * Appends an informational message to the desktop log.
     */
    @Synchronized
    fun info(message: String) {
        append("INFO", message)
    }

    /**
     * Appends an error message to the desktop log, optionally including throwable details on the
     * same line for quick inspection.
     */
    @Synchronized
    fun error(message: String, throwable: Throwable? = null) {
        append("ERROR", buildString {
            append(message)
            throwable?.let {
                append(" | ")
                append(it::class.simpleName ?: "Throwable")
                append(": ")
                append(it.message ?: "no message")
            }
        })
    }

    /**
     * Returns the absolute path to the current desktop log file.
     */
    fun logFilePath(): String = logPath.toString()

    /**
     * Returns persisted error entries in reverse chronological file order.
     *
     * Each entry preserves its original line index so follow-up actions such as deletion can target
     * the correct line in the underlying file.
     */
    fun errorEntries(): List<DesktopErrorLogEntry> {
        if (!Files.exists(logPath)) return emptyList()

        return runCatching {
            logPath.readLines()
                .mapIndexedNotNull { index, line ->
                    if ("[ERROR]" in line) DesktopErrorLogEntry(id = index, line = line) else null
                }
                .asReversed()
        }.getOrDefault(emptyList())
    }

    /**
     * Deletes a single error entry by its original log line index.
     *
     * Non-error lines and out-of-range ids are ignored to keep UI-triggered cleanup safe.
     */
    @Synchronized
    fun deleteError(id: Int) {
        if (!Files.exists(logPath)) return

        val lines = runCatching { logPath.readLines() }.getOrDefault(emptyList())
        if (id !in lines.indices) return
        if ("[ERROR]" !in lines[id]) return

        writeLines(lines.filterIndexed { index, _ -> index != id })
    }

    /**
     * Removes every error entry from the log file while preserving non-error lines.
     */
    @Synchronized
    fun clearErrors() {
        if (!Files.exists(logPath)) return

        val lines = runCatching { logPath.readLines() }.getOrDefault(emptyList())
        writeLines(lines.filterNot { "[ERROR]" in it })
    }

    private fun append(level: String, message: String) {
        val line = "${timestampFormatter.format(Instant.now())} [$level] $message\n"
        Files.writeString(logPath, line, java.nio.file.StandardOpenOption.APPEND)
    }

    private fun writeLines(lines: List<String>) {
        val text = if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n")
        logPath.writeText(text)
    }
}
