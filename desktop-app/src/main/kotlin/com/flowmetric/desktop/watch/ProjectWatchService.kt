package com.flowmetric.desktop.watch

import java.io.Closeable
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.io.path.isDirectory
import kotlin.io.path.name

class ProjectWatchService : Closeable {
    private var watchService: WatchService? = null
    private var watchJob: Job? = null
    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()

    fun start(
        scope: CoroutineScope,
        projectRoot: Path,
        onChange: (ProjectChangeBatch) -> Unit,
    ) {
        stop()
        if (!Files.exists(projectRoot)) return

        val service = FileSystems.getDefault().newWatchService()
        watchService = service
        registerRecursively(projectRoot, service)

        watchJob = scope.launch(Dispatchers.IO) {
            val pendingChanges = linkedMapOf<Path, ProjectFileChange>()
            var debounceJob: Job? = null

            while (isActive) {
                val key = try {
                    service.take()
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                    break
                }

                val directory = watchKeys[key]
                if (directory == null) {
                    key.reset()
                    continue
                }

                for (event in key.pollEvents()) {
                    val kind = event.kind()
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue

                    @Suppress("UNCHECKED_CAST")
                    val pathEvent = event as WatchEvent<Path>
                    val child = directory.resolve(pathEvent.context())

                    if (kind == StandardWatchEventKinds.ENTRY_CREATE && child.isDirectory()) {
                        registerRecursively(child, service)
                    }

                    classify(child, kind)?.let { change ->
                        pendingChanges[change.path] = change
                        debounceJob?.cancel()
                        debounceJob = launch {
                            delay(DEBOUNCE_MS)
                            if (pendingChanges.isNotEmpty()) {
                                onChange(ProjectChangeBatch(pendingChanges.values.toList()))
                                pendingChanges.clear()
                            }
                        }
                    }
                }

                if (!key.reset()) {
                    watchKeys.remove(key)
                }
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        watchKeys.clear()
        watchService?.close()
        watchService = null
    }

    override fun close() {
        stop()
    }

    private fun registerRecursively(root: Path, service: WatchService) {
        if (!Files.exists(root) || shouldSkipDirectory(root)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (shouldSkipDirectory(dir)) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE
                    }
                    val key = dir.register(
                        service,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                    )
                    watchKeys[key] = dir
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun classify(path: Path, kind: WatchEvent.Kind<*>): ProjectFileChange? {
        val normalized = path.toString()
        return when {
            ignoredPathFragments.any { normalized.contains(it) } -> null
            normalized.contains("/.flowmetric/events.json") ->
                ProjectFileChange(path = path, category = ProjectChangeCategory.TRACKING_DATA, kind = kind.toChangeKind())
            else ->
                ProjectFileChange(path = path, category = ProjectChangeCategory.PROJECT_FILES, kind = kind.toChangeKind())
        }
    }

    private fun shouldSkipDirectory(path: Path): Boolean {
        val name = path.name
        return skippedDirectoryNames.contains(name)
    }

    companion object {
        private const val DEBOUNCE_MS = 700L
        private val skippedDirectoryNames = setOf(".git", ".gradle", ".idea", "build", "out", "node_modules")
        private val ignoredPathFragments = listOf("/.git/", "/.gradle/", "/.idea/", "/build/", "/out/", "/node_modules/")
    }
}

data class ProjectChangeBatch(
    val changes: List<ProjectFileChange>,
)

data class ProjectFileChange(
    val path: Path,
    val category: ProjectChangeCategory,
    val kind: FileSystemChangeKind,
)

enum class ProjectChangeCategory {
    TRACKING_DATA,
    PROJECT_FILES,
}

enum class FileSystemChangeKind {
    CREATED,
    MODIFIED,
    DELETED,
}

private fun WatchEvent.Kind<*>.toChangeKind(): FileSystemChangeKind = when (this) {
    StandardWatchEventKinds.ENTRY_CREATE -> FileSystemChangeKind.CREATED
    StandardWatchEventKinds.ENTRY_DELETE -> FileSystemChangeKind.DELETED
    else -> FileSystemChangeKind.MODIFIED
}
