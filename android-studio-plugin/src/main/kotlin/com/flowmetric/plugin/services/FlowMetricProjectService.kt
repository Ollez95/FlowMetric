package com.flowmetric.plugin.services

import com.flowmetric.plugin.state.FlowMetricAppState
import com.flowmetric.shared.heuristics.HeuristicContext
import com.flowmetric.shared.heuristics.HeuristicScorer
import com.flowmetric.shared.model.ChangeEvent
import com.flowmetric.shared.model.ChangeMetadata
import com.flowmetric.shared.model.EventSource
import com.flowmetric.shared.model.FileLineDelta
import com.flowmetric.shared.model.TrackedProject
import com.flowmetric.shared.persistence.FlowMetricStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension

@Service(Service.Level.PROJECT)
class FlowMetricProjectService(private val project: Project) {
    private val scorer = HeuristicScorer()
    private val snapshotCache = ConcurrentHashMap<String, String>()
    private val eventCache = mutableListOf<ChangeEvent>()
    private val eventLock = Any()

    init {
        subscribeToExternalChanges()
    }

    fun trackedRootPath(): String? {
        val defaultRoot = project.basePath ?: return null
        return project.service<FlowMetricAppState>().selectedRoot(project.locationHash) ?: defaultRoot
    }

    fun updateTrackedRoot(path: String) {
        project.service<FlowMetricAppState>().updateSelectedRoot(project.locationHash, path)
    }

    fun processSave(file: VirtualFile, document: Document) {
        val previousText = snapshotCache[file.path]
            ?: runCatching { String(file.contentsToByteArray(), file.charset) }.getOrDefault(document.text)
        recordChange(
            file = file,
            previousText = previousText,
            currentText = document.text,
            source = EventSource.DOCUMENT_SAVE,
        )
    }

    fun processExternalChange(file: VirtualFile, previousText: String) {
        val currentText = readFileText(file) ?: return
        recordChange(
            file = file,
            previousText = previousText,
            currentText = currentText,
            source = EventSource.EXTERNAL_FILE_CHANGE,
        )
    }

    private fun recordChange(
        file: VirtualFile,
        previousText: String,
        currentText: String,
        source: EventSource,
    ) {
        if (!isTrackable(file) || previousText == currentText) {
            snapshotCache[file.path] = currentText
            return
        }

        synchronized(eventLock) {
            val delta = estimateLineDelta(previousText, currentText)
            if (delta.changedLines == 0) {
                snapshotCache[file.path] = currentText
                return
            }

            val now = Instant.now().toEpochMilli()
            val previousEvent = eventCache.lastOrNull { it.filePath == file.path }
            val previousGlobalEvent = eventCache.lastOrNull()
            val sessionId = resolveSessionId(now)
            val sessionEvents = eventCache.filter { it.sessionId == sessionId }
            val filesTouchedInSession = (sessionEvents.map { it.filePath } + file.path).distinct().size
            val sessionStart = sessionEvents.minOfOrNull { it.timestampEpochMillis } ?: now
            val millisSincePreviousEvent = previousGlobalEvent?.let { now - it.timestampEpochMillis }
            val snapshot = scorer.score(
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
                    currentSource = source,
                ),
            )

            val rootPath = trackedRootPath() ?: return
            val projectModel = TrackedProject(
                id = rootPath,
                rootPath = rootPath,
                selectedAtEpochMillis = now,
            )
            val event = ChangeEvent(
                id = UUID.randomUUID().toString(),
                projectId = projectModel.id,
                projectPath = rootPath,
                filePath = file.path,
                timestampEpochMillis = now,
                sessionId = sessionId,
                delta = delta,
                metadata = ChangeMetadata(
                    source = source,
                    fileExtension = Path.of(file.path).extension,
                    languageHint = file.fileType.name,
                    latestContentHash = currentText.sha256(),
                    millisSincePreviousEvent = millisSincePreviousEvent,
                    sessionEventIndex = sessionEvents.size + 1,
                    sessionDurationMillis = now - sessionStart,
                    filesTouchedInSession = filesTouchedInSession,
                ),
                snapshot = snapshot,
            )

            FlowMetricStore.projectStore(Path.of(rootPath)).appendEvent(event, projectModel)
            eventCache += event
            snapshotCache[file.path] = currentText
        }
    }

    private fun resolveSessionId(now: Long): String {
        val existing = eventCache.lastOrNull()
        return if (existing != null && now - existing.timestampEpochMillis <= SESSION_WINDOW_MS) {
            existing.sessionId
        } else {
            UUID.randomUUID().toString()
        }
    }

    private fun isTracked(file: VirtualFile): Boolean {
        val root = trackedRootPath() ?: return false
        return !file.isDirectory && file.path.startsWith(root)
    }

    private fun isTrackable(file: VirtualFile): Boolean =
        isTracked(file) &&
            !file.fileType.isBinary &&
            ignoredPathFragments.none { file.path.contains(it) }

    private fun readFileText(file: VirtualFile): String? =
        runCatching { String(file.contentsToByteArray(), file.charset) }.getOrNull()

    private fun subscribeToExternalChanges() {
        val beforeSnapshots = ConcurrentHashMap<String, String>()
        project.messageBus.connect(project).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun before(events: List<VFileEvent>) {
                    events.asSequence()
                        .filterIsInstance<VFileContentChangeEvent>()
                        .filter { shouldTrackExternalEvent(it) }
                        .forEach { event ->
                            val file = event.file
                            val previousText = snapshotCache[file.path] ?: readFileText(file) ?: return@forEach
                            beforeSnapshots[file.path] = previousText
                        }
                }

                override fun after(events: List<VFileEvent>) {
                    val candidates = events.asSequence()
                        .filterIsInstance<VFileContentChangeEvent>()
                        .filter { shouldTrackExternalEvent(it) }
                        .mapNotNull { event ->
                            val file = event.file
                            val previousText = beforeSnapshots.remove(file.path) ?: return@mapNotNull null
                            ExternalChangeCandidate(file = file, previousText = previousText)
                        }
                        .toList()
                    if (candidates.isEmpty()) return

                    AppExecutorUtil.getAppExecutorService().execute {
                        candidates.forEach { candidate ->
                            processExternalChange(candidate.file, candidate.previousText)
                        }
                    }
                }
            },
        )
    }

    private fun shouldTrackExternalEvent(event: VFileContentChangeEvent): Boolean {
        val file = event.file
        return event.requestor == null &&
            isTrackable(file) &&
            FileDocumentManager.getInstance().getCachedDocument(file)?.let { !it.isWritable } != true
    }

    private fun estimateLineDelta(previousText: String, currentText: String): FileLineDelta {
        val previousLines = previousText.lines()
        val currentLines = currentText.lines()
        if (previousLines == currentLines) {
            return FileLineDelta()
        }

        val diff = computeLineDiff(previousLines, currentLines)
        return FileLineDelta(
            inserted = diff.inserted,
            deleted = diff.deleted,
            largestInsertedBlock = diff.largestInsertedBlock,
            largestDeletedBlock = diff.largestDeletedBlock,
        )
    }

    private fun computeLineDiff(left: List<String>, right: List<String>): LineDiffSummary {
        if (left.isEmpty() && right.isEmpty()) {
            return LineDiffSummary()
        }

        val dp = Array(left.size + 1) { IntArray(right.size + 1) }
        for (leftIndex in left.indices.reversed()) {
            for (rightIndex in right.indices.reversed()) {
                dp[leftIndex][rightIndex] = if (left[leftIndex] == right[rightIndex]) {
                    dp[leftIndex + 1][rightIndex + 1] + 1
                } else {
                    maxOf(dp[leftIndex + 1][rightIndex], dp[leftIndex][rightIndex + 1])
                }
            }
        }

        var leftIndex = 0
        var rightIndex = 0
        var inserted = 0
        var deleted = 0
        var insertedRun = 0
        var deletedRun = 0
        var largestInsertedBlock = 0
        var largestDeletedBlock = 0

        fun flushRuns() {
            largestInsertedBlock = maxOf(largestInsertedBlock, insertedRun)
            largestDeletedBlock = maxOf(largestDeletedBlock, deletedRun)
            insertedRun = 0
            deletedRun = 0
        }

        while (leftIndex < left.size && rightIndex < right.size) {
            when {
                left[leftIndex] == right[rightIndex] -> {
                    flushRuns()
                    leftIndex++
                    rightIndex++
                }

                dp[leftIndex + 1][rightIndex] >= dp[leftIndex][rightIndex + 1] -> {
                    deleted++
                    deletedRun++
                    largestInsertedBlock = maxOf(largestInsertedBlock, insertedRun)
                    insertedRun = 0
                    leftIndex++
                }

                else -> {
                    inserted++
                    insertedRun++
                    largestDeletedBlock = maxOf(largestDeletedBlock, deletedRun)
                    deletedRun = 0
                    rightIndex++
                }
            }
        }

        while (leftIndex < left.size) {
            deleted++
            deletedRun++
            leftIndex++
        }
        while (rightIndex < right.size) {
            inserted++
            insertedRun++
            rightIndex++
        }
        flushRuns()

        return LineDiffSummary(
            inserted = inserted,
            deleted = deleted,
            largestInsertedBlock = largestInsertedBlock,
            largestDeletedBlock = largestDeletedBlock,
        )
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val SESSION_WINDOW_MS = 10 * 60 * 1000L
        private val ignoredPathFragments = listOf(
            "/.flowmetric/",
            "/.git/",
            "/.gradle/",
            "/.idea/",
            "/build/",
            "/out/",
            "/node_modules/",
        )
    }
}

private data class ExternalChangeCandidate(
    val file: VirtualFile,
    val previousText: String,
)

private data class LineDiffSummary(
    val inserted: Int = 0,
    val deleted: Int = 0,
    val largestInsertedBlock: Int = 0,
    val largestDeletedBlock: Int = 0,
)
