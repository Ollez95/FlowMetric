package com.flowmetric.shared.tracking

import com.flowmetric.shared.model.EventSource
import com.flowmetric.shared.persistence.FlowMetricStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

object CodexPatchRecorderMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 3) {
            "Usage: <projectRoot> <absoluteFilePath> <beforeSnapshotFile> [sourceLabel] [agentModel]"
        }

        val projectRoot = Path.of(args[0]).normalize()
        val absoluteFilePath = Path.of(args[1]).normalize()
        val beforeSnapshotPath = Path.of(args[2]).normalize()
        val sourceLabel = args.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "Codex"
        val agentModel = args.getOrNull(4)?.takeIf { it.isNotBlank() }

        require(Files.exists(projectRoot)) { "Project root does not exist: $projectRoot" }
        require(absoluteFilePath.startsWith(projectRoot)) {
            "Edited file must be inside the tracked project root."
        }
        require(beforeSnapshotPath.isRegularFile()) {
            "Before-snapshot file does not exist: $beforeSnapshotPath"
        }

        val previousText = beforeSnapshotPath.readText()
        val currentText = if (Files.exists(absoluteFilePath)) {
            absoluteFilePath.readText()
        } else {
            ""
        }

        val store = FlowMetricStore.projectStore(projectRoot)
        val existingEvents = store.read().events
        val prepared = ChangeEventFactory().build(
            ChangeEventRequest(
                projectPath = projectRoot.toString(),
                filePath = absoluteFilePath.toString(),
                fileExtension = absoluteFilePath.extension,
                sourceLabel = sourceLabel,
                agentModel = agentModel,
                languageHint = absoluteFilePath.extension.ifBlank { null },
                branchName = gitOutput(projectRoot, "rev-parse", "--abbrev-ref", "HEAD"),
                headCommitHash = gitOutput(projectRoot, "rev-parse", "HEAD"),
                previousText = previousText,
                currentText = currentText,
                source = EventSource.CODEX_PATCH,
                existingEvents = existingEvents,
                timestampEpochMillis = Instant.now().toEpochMilli(),
            ),
        ) ?: return

        store.appendEvent(prepared.event, prepared.project)
        println("Recorded CODEX_PATCH for ${absoluteFilePath.fileName}")
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
