package com.flowmetric.desktop.tracking

import com.flowmetric.desktop.watch.FileSystemChangeKind
import com.flowmetric.desktop.watch.ProjectFileChange
import com.flowmetric.desktop.watch.ProjectChangeCategory
import com.flowmetric.shared.analytics.LineDiffEstimator
import com.flowmetric.shared.analytics.LinePatchBuilder
import com.flowmetric.shared.config.ProjectFileRules
import com.flowmetric.shared.heuristics.HeuristicContext
import com.flowmetric.shared.heuristics.HeuristicScorer
import com.flowmetric.shared.model.ChangeEvent
import com.flowmetric.shared.model.ChangeMetadata
import com.flowmetric.shared.model.EventSource
import com.flowmetric.shared.model.FlowMetricProjectConfig
import com.flowmetric.shared.model.TrackedProject
import com.flowmetric.shared.persistence.FlowMetricProjectConfigStore
import com.flowmetric.shared.persistence.FlowMetricStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

class DesktopExternalEventRecorder(
    private val scorer: HeuristicScorer = HeuristicScorer(),
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
        val snapshot = store.read()
        var appended = 0

        changes
            .filter { it.category == ProjectChangeCategory.PROJECT_FILES }
            .forEach { change ->
                appended += recordSingleChange(projectRoot, change, config, snapshot.events, store)
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
    ): Int {
        val filePath = change.path
        if (!isTrackable(projectRoot, filePath, config)) return 0

        val previousText = when (change.kind) {
            FileSystemChangeKind.CREATED -> ""
            else -> textCache[filePath.toString()] ?: return 0
        }
        val currentText = when (change.kind) {
            FileSystemChangeKind.DELETED -> ""
            else -> readText(filePath) ?: return 0
        }

        if (previousText == currentText) return 0
        val delta = LineDiffEstimator.estimate(previousText, currentText)
        if (delta.changedLines == 0) {
            textCache[filePath.toString()] = currentText
            return 0
        }

        val now = Instant.now().toEpochMilli()
        val fileEvents = existingEvents.filter { it.filePath == filePath.toString() }
        val previousEvent = fileEvents.maxByOrNull { it.timestampEpochMillis }
        val previousGlobalEvent = existingEvents.maxByOrNull { it.timestampEpochMillis }
        val sessionId = resolveSessionId(existingEvents, now)
        val sessionEvents = existingEvents.filter { it.sessionId == sessionId }
        val sessionStart = sessionEvents.minOfOrNull { it.timestampEpochMillis } ?: now
        val millisSincePreviousEvent = previousGlobalEvent?.let { now - it.timestampEpochMillis }
        val filesTouchedInSession = (sessionEvents.map { it.filePath } + filePath.toString()).distinct().size
        val snapshotHeuristic = scorer.score(
            insertedLines = delta.inserted,
            deletedLines = delta.deleted,
            timestampEpochMillis = now,
            largestInsertedBlock = delta.largestInsertedBlock,
            context = HeuristicContext(
                previousEventForFile = previousEvent,
                previousEventsInSession = sessionEvents,
                millisSincePreviousEvent = millisSincePreviousEvent,
                sessionDurationMillis = now - sessionStart,
                filesTouchedInSession = filesTouchedInSession,
                currentSource = EventSource.EXTERNAL_FILE_CHANGE,
            ),
        )

        val contentHash = currentText.sha256()
        val duplicate = existingEvents.any {
            it.filePath == filePath.toString() &&
                it.metadata.latestContentHash == contentHash &&
                now - it.timestampEpochMillis <= DEDUPE_WINDOW_MS
        }
        if (duplicate) {
            textCache[filePath.toString()] = currentText
            return 0
        }

        val project = TrackedProject(
            id = projectRoot.toString(),
            rootPath = projectRoot.toString(),
            selectedAtEpochMillis = now,
        )
        val event = ChangeEvent(
            id = UUID.randomUUID().toString(),
            projectId = project.id,
            projectPath = project.rootPath,
            filePath = filePath.toString(),
            timestampEpochMillis = now,
            sessionId = sessionId,
            delta = delta,
            metadata = ChangeMetadata(
                source = EventSource.EXTERNAL_FILE_CHANGE,
                fileExtension = filePath.extension,
                languageHint = filePath.fileName.toString().substringAfterLast('.', missingDelimiterValue = ""),
                latestContentHash = contentHash,
                linePatch = LinePatchBuilder.build(previousText, currentText),
                millisSincePreviousEvent = millisSincePreviousEvent,
                sessionEventIndex = sessionEvents.size + 1,
                sessionDurationMillis = now - sessionStart,
                filesTouchedInSession = filesTouchedInSession,
            ),
            snapshot = snapshotHeuristic,
        )

        store.appendEvent(event, project)
        textCache[filePath.toString()] = currentText
        if (change.kind == FileSystemChangeKind.DELETED) {
            textCache.remove(filePath.toString())
        }
        return 1
    }

    private fun resolveSessionId(existingEvents: List<ChangeEvent>, now: Long): String {
        val lastEvent = existingEvents.maxByOrNull { it.timestampEpochMillis }
        return if (lastEvent != null && now - lastEvent.timestampEpochMillis <= SESSION_WINDOW_MS) {
            lastEvent.sessionId
        } else {
            UUID.randomUUID().toString()
        }
    }

    private fun isTrackable(projectRoot: Path, path: Path, config: FlowMetricProjectConfig): Boolean {
        if (!path.isRegularFile() && Files.exists(path)) return false
        return ProjectFileRules.isTrackable(projectRoot, path, config)
    }

    private fun readText(path: Path): String? =
        runCatching { Files.readString(path) }.getOrNull()

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
    companion object {
        private const val SESSION_WINDOW_MS = 10 * 60 * 1000L
        private const val DEDUPE_WINDOW_MS = 5_000L
    }
}
