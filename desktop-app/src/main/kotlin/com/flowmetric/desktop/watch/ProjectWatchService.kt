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

/**
 * Monitors a project directory for file system changes using [WatchService].
 *
 * This service recursively watches a specified project root directory for file creation,
 * modification, and deletion events. It debounces these events to avoid processing
 * rapid, successive changes, and then emits them as a [ProjectChangeBatch].
 *
 * The service is designed to be resilient to interruptions and can be started and stopped
 * as needed. It also filters out irrelevant directories and files (e.g., `.git`, `build`).
 */
class ProjectWatchService : Closeable {
    private var watchService: WatchService? = null
    private var watchJob: Job? = null
    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()

    /**
     * Starts monitoring the specified [projectRoot] for file changes.
     *
     * If the service is already running, it will be stopped and restarted. The provided [scope]
     * is used to launch a coroutine that listens for file events.
     *
     * Change events are collected and debounced. After a quiet period of [DEBOUNCE_MS], a
     * [ProjectChangeBatch] is created and passed to the [onChange] callback.
     *
     * @param scope The [CoroutineScope] to launch the watch job in.
     * @param projectRoot The root directory of the project to monitor.
     * @param onChange A callback function that receives batches of file changes.
     */
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

    /**
     * Stops the file watch service and cancels the monitoring job.
     *
     * This method clears all registered watch keys and closes the [WatchService].
     */
    fun stop() {
        watchJob?.cancel()
        watchJob = null
        watchKeys.clear()
        watchService?.close()
        watchService = null
    }

    /**
     * Closes the service and releases all associated resources by calling [stop].
     */
    override fun close() {
        stop()
    }

    /**
     * Recursively registers a directory and all its subdirectories with the [WatchService].
     *
     * This method walks the file tree starting from the [root] path. Any directories that
     * are not explicitly skipped by [shouldSkipDirectory] will be registered to watch for
     * file creation, deletion, and modification events.
     *
     * @param root The root path to start registration from.
     * @param service The [WatchService] instance to register with.
     */
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

    /**
     * Classifies a file system event into a [ProjectFileChange] based on its path and kind.
     *
     * This method filters out events from ignored path fragments and categorizes the change
     * based on whether it pertains to FlowMetric's own tracking data or general project files.
     *
     * @param path The file path associated with the event.
     * @param kind The kind of event (e.g., create, delete, modify).
     * @return A [ProjectFileChange] if the event is relevant, or `null` if it should be ignored.
     */
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

    /**
     * Determines if a directory should be skipped by the watch service.
     *
     * This is used to avoid watching directories that typically contain generated files,
     * dependencies, or other non-source content (e.g., `.git`, `build`).
     *
     * @param path The directory path to check.
     * @return `true` if the directory should be skipped, `false` otherwise.
     */
    private fun shouldSkipDirectory(path: Path): Boolean {
        val name = path.name
        return skippedDirectoryNames.contains(name)
    }

    companion object {
        /** The debounce time in milliseconds to wait for more file changes before processing. */
        private const val DEBOUNCE_MS = 700L

        /** A set of directory names that should be completely ignored by the watch service. */
        private val skippedDirectoryNames = setOf(".git", ".gradle", ".idea", ".intellijPlatform", ".kotlin", ".run", "build", "out", "node_modules")

        /** A list of path fragments used to filter out irrelevant file change events. */
        private val ignoredPathFragments = listOf(
            "/.git/",
            "/.gradle/",
            "/.idea/",
            "/.intellijPlatform/",
            "/.kotlin/",
            "/.run/",
            "/build/",
            "/out/",
            "/node_modules/",
        )
    }
}

/**
 * A batch of file system changes that occurred within a debounced time window.
 *
 * @property changes The list of individual file changes.
 */
data class ProjectChangeBatch(
    val changes: List<ProjectFileChange>,
)

/**
 * Represents a single file change detected by the [ProjectWatchService].
 *
* @property path The absolute path to the file that changed.
 * @property category The category of the change (e.g., tracking data or project file).
 * @property kind The type of file system operation (created, modified, or deleted).
 */
data class ProjectFileChange(
    val path: Path,
    val category: ProjectChangeCategory,
    val kind: FileSystemChangeKind,
)

/**
 * Categorizes the type of content a file change pertains to.
 */
enum class ProjectChangeCategory {
    /** The change is related to FlowMetric's internal tracking data. */
    TRACKING_DATA,

    /** The change is related to general project source files. */
    PROJECT_FILES,
}

/**
 * Represents the type of a file system change.
 */
enum class FileSystemChangeKind {
    /** A new file or directory was created. */
    CREATED,

    /** An existing file was modified. */
    MODIFIED,

    /** A file or directory was deleted. */
    DELETED,
}

/**
 * Converts a [WatchEvent.Kind] from the Java NIO API to the corresponding [FileSystemChangeKind].
 */
private fun WatchEvent.Kind<*>.toChangeKind(): FileSystemChangeKind = when (this) {
    StandardWatchEventKinds.ENTRY_CREATE -> FileSystemChangeKind.CREATED
    StandardWatchEventKinds.ENTRY_DELETE -> FileSystemChangeKind.DELETED
    else -> FileSystemChangeKind.MODIFIED
}
