package com.flowmetric.desktop.tracking

import com.flowmetric.desktop.watch.FileSystemChangeKind
import com.flowmetric.desktop.watch.ProjectFileChange
import com.flowmetric.desktop.watch.ProjectChangeCategory
import com.flowmetric.shared.config.ProjectFileRules
import com.flowmetric.shared.model.ChangeEvent
import com.flowmetric.shared.model.EventSource
import com.flowmetric.shared.model.FlowMetricProjectConfig
import com.flowmetric.shared.persistence.FlowMetricProjectConfigStore
import com.flowmetric.shared.persistence.FlowMetricStore
import com.flowmetric.shared.tracking.ChangeEventFactory
import com.flowmetric.shared.tracking.ChangeEventRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

class DesktopExternalEventRecorder(
    private val changeEventFactory: ChangeEventFactory = ChangeEventFactory(),
) {
    private val textCache = ConcurrentHashMap<String, String>()
    private val cacheInitialized = ConcurrentHashMap.newKeySet<String>()

    fun prime(projectRoot: Path) {
        if (!Files.exists(projectRoot)) return
        val config = FlowMetricProjectConfigStore.projectConfigStore(projectRoot).readOrCreate()
        val rootKey = projectRoot.toString()
        if (!cacheInitialized.add(rootKey)) return

        Files.walk(projectRoot).use { paths ->
            paths.iterator().asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { isTrackable(projectRoot, it, config) }
                .forEach { file ->
                    readText(file)?.let { textCache[file.toString()] = it }
                }
        }
    }

    fun recordChanges(projectRoot: Path, changes: List<ProjectFileChange>): Int {
        if (!Files.exists(projectRoot) || changes.isEmpty()) return 0
        prime(projectRoot)
        val config = FlowMetricProjectConfigStore.projectConfigStore(projectRoot).readOrCreate()

        val store = FlowMetricStore.projectStore(projectRoot)
        val knownEvents = store.read().events.toMutableList()
        var appended = 0

        changes
            .filter { it.category == ProjectChangeCategory.PROJECT_FILES }
            .forEach { change ->
                val recordedEvent = recordSingleChange(projectRoot, change, config, knownEvents, store)
                if (recordedEvent != null) {
                    knownEvents += recordedEvent
                    appended += 1
                }
            }

        return appended
    }

    fun resetProject(projectRoot: Path) {
        val rootKey = projectRoot.toString()
        cacheInitialized.remove(rootKey)
        textCache.keys.removeIf { it.startsWith(rootKey) }
    }

    private fun recordSingleChange(
        projectRoot: Path,
        change: ProjectFileChange,
        config: FlowMetricProjectConfig,
        existingEvents: List<ChangeEvent>,
        store: FlowMetricStore,
    ): ChangeEvent? {
        val filePath = change.path
        if (!isTrackable(projectRoot, filePath, config)) return null

        val previousText = when (change.kind) {
            FileSystemChangeKind.CREATED -> ""
            else -> textCache[filePath.toString()] ?: return null
        }
        val currentText = when (change.kind) {
            FileSystemChangeKind.DELETED -> ""
            else -> readText(filePath) ?: return null
        }

        val gitContext = resolveGitContext(projectRoot)
        val preparedEvent = changeEventFactory.build(
            ChangeEventRequest(
                projectPath = projectRoot.toString(),
                filePath = filePath.toString(),
                fileExtension = filePath.extension,
                languageHint = filePath.fileName.toString().substringAfterLast('.', missingDelimiterValue = ""),
                branchName = gitContext?.branchName,
                headCommitHash = gitContext?.headCommitHash,
                previousText = previousText,
                currentText = currentText,
                source = EventSource.EXTERNAL_FILE_CHANGE,
                existingEvents = existingEvents,
                timestampEpochMillis = System.currentTimeMillis(),
            ),
        ) ?: run {
            textCache[filePath.toString()] = currentText
            return null
        }

        val contentHash = preparedEvent.event.metadata.latestContentHash
        val duplicate = existingEvents.any {
            it.filePath == filePath.toString() &&
                it.metadata.latestContentHash == contentHash &&
                preparedEvent.event.timestampEpochMillis - it.timestampEpochMillis <= DEDUPE_WINDOW_MS
        }
        if (duplicate) {
            textCache[filePath.toString()] = currentText
            return null
        }

        store.appendEvent(preparedEvent.event, preparedEvent.project)
        textCache[filePath.toString()] = currentText
        if (change.kind == FileSystemChangeKind.DELETED) {
            textCache.remove(filePath.toString())
        }
        return preparedEvent.event
    }

    private fun isTrackable(projectRoot: Path, path: Path, config: FlowMetricProjectConfig): Boolean {
        if (!path.isRegularFile() && Files.exists(path)) return false
        return ProjectFileRules.isTrackable(projectRoot, path, config)
    }

    private fun readText(path: Path): String? =
        runCatching { Files.readString(path) }.getOrNull()

    private fun resolveGitContext(projectRoot: Path): GitContext? {
        val process = runCatching {
            ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD", "HEAD")
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null

        val output = process.inputStream.bufferedReader().use { it.readLines() }
        if (process.waitFor() != 0 || output.size < 2) return null

        val branchName = output.first().trim().ifBlank { null }
        val headCommitHash = output.last().trim().ifBlank { null }
        if (branchName == null && headCommitHash == null) return null

        return GitContext(
            branchName = branchName,
            headCommitHash = headCommitHash,
        )
    }

    companion object {
        private const val DEDUPE_WINDOW_MS = 5_000L
    }
}

private data class GitContext(
    val branchName: String?,
    val headCommitHash: String?,
)
