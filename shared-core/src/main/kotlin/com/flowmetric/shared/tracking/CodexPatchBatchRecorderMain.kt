package com.flowmetric.shared.tracking

import com.flowmetric.shared.model.ChangeEvent
import com.flowmetric.shared.model.EventSource
import com.flowmetric.shared.model.TrackedProject
import com.flowmetric.shared.persistence.FlowMetricStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.readText

object CodexPatchBatchRecorderMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) {
            "Usage: <projectRoot> <manifestFile> [sourceLabel] [agentModel]"
        }

        val projectRoot = Path.of(args[0]).normalize()
        val manifestPath = Path.of(args[1]).normalize()
        val sourceLabel = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "Codex"
        val agentModel = args.getOrNull(3)?.takeIf { it.isNotBlank() }

        require(Files.exists(projectRoot)) { "Project root does not exist: $projectRoot" }
        require(manifestPath.isRegularFile()) { "Manifest file does not exist: $manifestPath" }

        val manifestEntries = manifestPath.readLines()
            .mapIndexedNotNull { index, line ->
                parseManifestLine(line, index + 1)
            }
        if (manifestEntries.isEmpty()) {
            println("No Codex edits found in manifest.")
            return
        }

        val branchName = gitOutput(projectRoot, "rev-parse", "--abbrev-ref", "HEAD")
        val headCommitHash = gitOutput(projectRoot, "rev-parse", "HEAD")
        val store = FlowMetricStore.projectStore(projectRoot)
        val preparedEvents = mutableListOf<ChangeEvent>()
        val preparedProjects = mutableListOf<TrackedProject>()
        val mutableExistingEvents = store.read().events.toMutableList()
        val baseTimestamp = Instant.now().toEpochMilli()

        manifestEntries.forEachIndexed { index, entry ->
            require(entry.absoluteFilePath.startsWith(projectRoot)) {
                "Edited file must be inside the tracked project root: ${entry.absoluteFilePath}"
            }
            require(entry.beforeSnapshotPath.isRegularFile()) {
                "Before-snapshot file does not exist: ${entry.beforeSnapshotPath}"
            }

            val previousText = entry.beforeSnapshotPath.readText()
            val currentText = if (Files.exists(entry.absoluteFilePath)) {
                entry.absoluteFilePath.readText()
            } else {
                ""
            }

            val prepared = ChangeEventFactory().build(
                ChangeEventRequest(
                    projectPath = projectRoot.toString(),
                    filePath = entry.absoluteFilePath.toString(),
                    fileExtension = entry.absoluteFilePath.extension,
                    sourceLabel = sourceLabel,
                    agentModel = agentModel,
                    languageHint = entry.absoluteFilePath.extension.ifBlank { null },
                    branchName = branchName,
                    headCommitHash = headCommitHash,
                    previousText = previousText,
                    currentText = currentText,
                    source = EventSource.CODEX_PATCH,
                    existingEvents = mutableExistingEvents,
                    timestampEpochMillis = baseTimestamp + index,
                ),
            ) ?: return@forEachIndexed

            mutableExistingEvents += prepared.event
            preparedEvents += prepared.event
            preparedProjects += prepared.project
        }

        store.appendEvents(preparedEvents, preparedProjects)
        println("Recorded ${preparedEvents.size} CODEX_PATCH event(s)")
    }

    private fun parseManifestLine(line: String, lineNumber: Int): ManifestEntry? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return null

        val parts = trimmed.split('\t')
        require(parts.size >= 2) {
            "Invalid manifest line $lineNumber. Expected <absolute-file-path><TAB><before-snapshot-file>."
        }

        return ManifestEntry(
            absoluteFilePath = Path.of(parts[0]).normalize(),
            beforeSnapshotPath = Path.of(parts[1]).normalize(),
        )
    }

    private fun gitOutput(projectRoot: Path, vararg args: String): String? {
        val process = runCatching {
            ProcessBuilder(listOf("git", *args))
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null

        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        return if (process.waitFor() == 0) output.ifBlank { null } else null
    }

}

private data class ManifestEntry(
    val absoluteFilePath: Path,
    val beforeSnapshotPath: Path,
)
