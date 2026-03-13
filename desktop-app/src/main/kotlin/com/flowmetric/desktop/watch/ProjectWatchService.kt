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
        onChange: (ProjectChange) -> Unit,
    ) {
        stop()
        if (!Files.exists(projectRoot)) return

        val service = FileSystems.getDefault().newWatchService()
        watchService = service
        registerRecursively(projectRoot, service)

        watchJob = scope.launch(Dispatchers.IO) {
            var pendingChange: ProjectChange? = null
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

                    classify(child)?.let { change ->
                        pendingChange = merge(pendingChange, change)
                        debounceJob?.cancel()
                        debounceJob = launch {
                            delay(DEBOUNCE_MS)
                            pendingChange?.let(onChange)
                            pendingChange = null
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

    private fun classify(path: Path): ProjectChange? {
        val normalized = path.toString()
        return when {
            ignoredPathFragments.any { normalized.contains(it) } -> null
            normalized.contains("/.flowmetric/events.json") -> ProjectChange.TRACKING_DATA
            else -> ProjectChange.PROJECT_FILES
        }
    }

    private fun shouldSkipDirectory(path: Path): Boolean {
        val name = path.name
        return skippedDirectoryNames.contains(name)
    }

    private fun merge(current: ProjectChange?, incoming: ProjectChange): ProjectChange =
        when {
            current == null -> incoming
            current == ProjectChange.PROJECT_FILES || incoming == ProjectChange.PROJECT_FILES -> ProjectChange.PROJECT_FILES
            else -> ProjectChange.TRACKING_DATA
        }

    companion object {
        private const val DEBOUNCE_MS = 700L
        private val skippedDirectoryNames = setOf(".git", ".gradle", ".idea", "build", "out", "node_modules")
        private val ignoredPathFragments = listOf("/.git/", "/.gradle/", "/.idea/", "/build/", "/out/", "/node_modules/")
    }
}

enum class ProjectChange {
    TRACKING_DATA,
    PROJECT_FILES,
}
