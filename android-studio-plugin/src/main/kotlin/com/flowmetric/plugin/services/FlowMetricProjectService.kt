package com.flowmetric.plugin.services

import com.flowmetric.plugin.state.FlowMetricAppState
import com.flowmetric.shared.analytics.LineDiffEstimator
import com.flowmetric.shared.analytics.LinePatchBuilder
import com.flowmetric.shared.config.ProjectFileRules
import com.flowmetric.shared.heuristics.HeuristicContext
import com.flowmetric.shared.heuristics.HeuristicScorer
import com.flowmetric.shared.model.ChangeEvent
import com.flowmetric.shared.model.ChangeMetadata
import com.flowmetric.shared.model.EventSource
import com.flowmetric.shared.model.FileLineDelta
import com.flowmetric.shared.model.FlowMetricProjectConfig
import com.flowmetric.shared.model.TrackedProject
import com.flowmetric.shared.persistence.FlowMetricProjectConfigStore
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
    @Volatile private var cachedConfigRoot: String? = null
    @Volatile private var cachedProjectConfig: FlowMetricProjectConfig = FlowMetricProjectConfig()

    init {
        subscribeToExternalChanges()
    }

    fun trackedRootPath(): String? {
        val defaultRoot = project.basePath ?: return null
        return project.service<FlowMetricAppState>().selectedRoot(project.locationHash) ?: defaultRoot
    }

    fun updateTrackedRoot(path: String) {
        project.service<FlowMetricAppState>().updateSelectedRoot(project.locationHash, path)
        cachedConfigRoot = null
        cachedProjectConfig = loadProjectConfig(path)
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
                    linePatch = LinePatchBuilder.build(previousText, currentText),
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

    private fun isTrackable(file: VirtualFile): Boolean {
        val root = trackedRootPath() ?: return false
        return isTracked(file) &&
            !file.fileType.isBinary &&
            ProjectFileRules.isTrackable(Path.of(root), Path.of(file.path), loadProjectConfig(root))
    }

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

    private fun estimateLineDelta(previousText: String, currentText: String): FileLineDelta =
        LineDiffEstimator.estimate(previousText, currentText)

    private fun loadProjectConfig(rootPath: String): FlowMetricProjectConfig {
        if (cachedConfigRoot == rootPath) return cachedProjectConfig
        val config = FlowMetricProjectConfigStore.projectConfigStore(Path.of(rootPath)).readOrCreate()
        cachedConfigRoot = rootPath
        cachedProjectConfig = config
        return config
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val SESSION_WINDOW_MS = 10 * 60 * 1000L
    }
}

private data class ExternalChangeCandidate(
    val file: VirtualFile,
    val previousText: String,
)
