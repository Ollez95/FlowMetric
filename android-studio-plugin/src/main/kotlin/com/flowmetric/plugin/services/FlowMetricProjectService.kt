package com.flowmetric.plugin.services

import com.flowmetric.plugin.state.FlowMetricAppState
import com.flowmetric.shared.config.ProjectFileRules
import com.flowmetric.shared.model.ChangeEvent
import com.flowmetric.shared.model.EventSource
import com.flowmetric.shared.model.FlowMetricProjectConfig
import com.flowmetric.shared.persistence.FlowMetricProjectConfigStore
import com.flowmetric.shared.persistence.FlowMetricStore
import com.flowmetric.shared.tracking.ChangeEventFactory
import com.flowmetric.shared.tracking.ChangeEventRequest
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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension

@Service(Service.Level.PROJECT)
class FlowMetricProjectService(private val project: Project) {
    private val changeEventFactory = ChangeEventFactory()
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
            val rootPath = trackedRootPath() ?: return
            val gitContext = resolveGitContext(Path.of(rootPath))
            val preparedEvent = changeEventFactory.build(
                ChangeEventRequest(
                    projectPath = rootPath,
                    filePath = file.path,
                    fileExtension = Path.of(file.path).extension,
                    languageHint = file.fileType.name,
                    branchName = gitContext?.branchName,
                    headCommitHash = gitContext?.headCommitHash,
                    previousText = previousText,
                    currentText = currentText,
                    source = source,
                    existingEvents = eventCache,
                    timestampEpochMillis = Instant.now().toEpochMilli(),
                ),
            ) ?: run {
                snapshotCache[file.path] = currentText
                return
            }

            FlowMetricStore.projectStore(Path.of(rootPath)).appendEvent(preparedEvent.event, preparedEvent.project)
            eventCache += preparedEvent.event
            snapshotCache[file.path] = currentText
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

    private fun loadProjectConfig(rootPath: String): FlowMetricProjectConfig {
        if (cachedConfigRoot == rootPath) return cachedProjectConfig
        val config = FlowMetricProjectConfigStore.projectConfigStore(Path.of(rootPath)).readOrCreate()
        cachedConfigRoot = rootPath
        cachedProjectConfig = config
        return config
    }

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

}

private data class ExternalChangeCandidate(
    val file: VirtualFile,
    val previousText: String,
)

private data class GitContext(
    val branchName: String?,
    val headCommitHash: String?,
)
