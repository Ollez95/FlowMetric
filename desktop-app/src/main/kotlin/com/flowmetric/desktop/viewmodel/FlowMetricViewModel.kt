package com.flowmetric.desktop.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flowmetric.desktop.install.TrackingInstaller
import com.flowmetric.desktop.git.GitDiffService
import com.flowmetric.desktop.git.GitDiffDocument
import com.flowmetric.desktop.logging.DesktopErrorLogEntry
import com.flowmetric.desktop.logging.FlowMetricDesktopLogger
import com.flowmetric.desktop.persistence.DesktopAppSettings
import com.flowmetric.desktop.persistence.DesktopAppSettingsStore
import com.flowmetric.desktop.persistence.RecentProjectsStore
import com.flowmetric.desktop.tracking.DesktopExternalEventRecorder
import com.flowmetric.desktop.watch.ProjectChangeBatch
import com.flowmetric.desktop.watch.ProjectChangeCategory
import com.flowmetric.desktop.watch.ProjectWatchService
import com.flowmetric.shared.analytics.AnalyticsEngine
import com.flowmetric.shared.model.AnalyticsFilter
import com.flowmetric.shared.model.ConfidenceLevel
import com.flowmetric.shared.model.DashboardMetrics
import com.flowmetric.shared.model.FlowMetricSnapshot
import com.flowmetric.shared.model.FlowMetricProjectConfig
import com.flowmetric.shared.model.GitCommitSummary
import com.flowmetric.shared.model.GitFileObservation
import com.flowmetric.shared.model.GitWorkingTreeSummary
import com.flowmetric.shared.persistence.FlowMetricProjectConfigStore
import com.flowmetric.shared.persistence.FlowMetricStore
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

class FlowMetricViewModel(
    private val analyticsEngine: AnalyticsEngine = AnalyticsEngine(),
    private val gitDiffService: GitDiffService = GitDiffService(),
    private val projectWatchService: ProjectWatchService = ProjectWatchService(),
    private val externalEventRecorder: DesktopExternalEventRecorder = DesktopExternalEventRecorder(),
    private val recentProjectsStore: RecentProjectsStore = RecentProjectsStore(),
    private val appSettingsStore: DesktopAppSettingsStore = DesktopAppSettingsStore(),
    private val trackingInstaller: TrackingInstaller = TrackingInstaller(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private var refreshJob: Job? = null
    private var watchRefreshJob: Job? = null
    private var cachedProjectLines: Int? = null
    private var cachedProjectFiles: Int? = null
    private var cachedProjectPath: String? = null

    var selectedProjectPath by mutableStateOf("")
        private set

    var projectPathInput by mutableStateOf("")
        private set

    var selectedConfidence by mutableStateOf(ConfidenceLevel.entries.toSet())
        private set

    var appSettings by mutableStateOf(appSettingsStore.read())
        private set

    var lookbackDays by mutableStateOf(appSettings.defaultLookbackDays)
        private set

    var snapshot by mutableStateOf(FlowMetricSnapshot())
        private set

    var dashboard by mutableStateOf<DashboardMetrics?>(null)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var statusMessage by mutableStateOf<String?>(null)
        private set

    val desktopLogPath: String = FlowMetricDesktopLogger.logFilePath()

    var errorLogs by mutableStateOf(emptyList<DesktopErrorLogEntry>())
        private set

    var gitSummary by mutableStateOf<GitWorkingTreeSummary?>(null)
        private set

    var selectedGitObservation by mutableStateOf<GitFileObservation?>(null)
        private set

    var selectedGitCommit by mutableStateOf<GitCommitSummary?>(null)
        private set

    var gitDiffPreview by mutableStateOf<String?>(null)
        private set

    var gitDiffDocument by mutableStateOf<GitDiffDocument?>(null)
        private set

    var isGitReverting by mutableStateOf(false)
        private set

    var projectConfig by mutableStateOf(FlowMetricProjectConfig())
        private set

    var recentProjects by mutableStateOf(emptyList<String>())
        private set

    init {
        recentProjects = recentProjectsStore.read()
        refreshErrorLogs()
        if (appSettings.reopenLastProjectOnLaunch) {
            recentProjects.firstOrNull()?.let { recentProject ->
                if (runCatching { Path.of(recentProject) }.getOrNull()?.toFile()?.exists() == true) {
                    setProjectPath(recentProject)
                }
            }
        }
    }

    fun updateProjectPathInput(path: String) {
        projectPathInput = path
    }

    fun setProjectPath(path: String) {
        val normalizedPath = path.trim()
        val isProjectSwitch = normalizedPath != selectedProjectPath
        selectedProjectPath = normalizedPath
        projectPathInput = normalizedPath
        recentProjectsStore.add(normalizedPath)
        recentProjects = recentProjectsStore.read()
        cachedProjectPath = null
        cachedProjectLines = null
        cachedProjectFiles = null
        projectConfig = FlowMetricProjectConfigStore.projectConfigStore(Path.of(normalizedPath)).readOrCreate()
        if (isProjectSwitch) {
            refreshJob?.cancel()
            dashboard = null
            snapshot = FlowMetricSnapshot()
            gitSummary = null
            selectedGitObservation = null
            selectedGitCommit = null
            gitDiffPreview = null
            gitDiffDocument = null
            isLoading = true
            statusMessage = "Loading project analytics..."
        }
        scope.launch(Dispatchers.IO) {
            externalEventRecorder.prime(Path.of(normalizedPath))
        }
        startWatchingProject(normalizedPath)
        refresh(recountProjectLines = true)
    }

    fun removeRecentProject(path: String) {
        val normalizedPath = path.trim()
        if (normalizedPath.isBlank()) return

        recentProjectsStore.remove(normalizedPath)
        recentProjects = recentProjectsStore.read()

        if (selectedProjectPath == normalizedPath) {
            selectedProjectPath = ""
            projectPathInput = ""
            cachedProjectPath = null
            cachedProjectLines = null
            cachedProjectFiles = null
            dashboard = null
            snapshot = FlowMetricSnapshot()
            gitSummary = null
            selectedGitObservation = null
            selectedGitCommit = null
            gitDiffPreview = null
            gitDiffDocument = null
            projectConfig = FlowMetricProjectConfig()
            isLoading = false
            statusMessage = "Recent project removed."
            projectWatchService.stop()
        }
    }

    fun reorderRecentProjects(paths: List<String>) {
        val normalizedPaths = paths
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        recentProjectsStore.replaceAll(normalizedPaths)
        recentProjects = recentProjectsStore.read()
    }

    fun applySelectedProjectPathOrRefresh() {
        val normalizedPath = projectPathInput.trim()
        if (normalizedPath.isBlank()) {
            selectedProjectPath = ""
            dashboard = null
            snapshot = FlowMetricSnapshot()
            gitSummary = null
            selectedGitObservation = null
            selectedGitCommit = null
            gitDiffPreview = null
            gitDiffDocument = null
            projectConfig = FlowMetricProjectConfig()
            statusMessage = null
            projectWatchService.stop()
            return
        }

        if (normalizedPath != selectedProjectPath) {
            setProjectPath(normalizedPath)
        } else {
            refresh(recountProjectLines = true)
        }
    }

    fun updateLookbackDays(days: Int) {
        lookbackDays = days
        refresh(recountProjectLines = false)
    }

    fun saveAppSettings(settings: DesktopAppSettings) {
        val normalizedSettings = settings.normalized()
        appSettingsStore.write(normalizedSettings)
        appSettings = normalizedSettings
        lookbackDays = normalizedSettings.defaultLookbackDays
        statusMessage = "Settings saved."
        if (selectedProjectPath.isNotBlank()) {
            refresh(recountProjectLines = false)
        }
    }

    fun refreshErrorLogs() {
        errorLogs = FlowMetricDesktopLogger.errorEntries()
    }

    fun deleteErrorLog(id: Int) {
        FlowMetricDesktopLogger.deleteError(id)
        refreshErrorLogs()
    }

    fun clearErrorLogs() {
        FlowMetricDesktopLogger.clearErrors()
        refreshErrorLogs()
    }

    fun toggleConfidence(level: ConfidenceLevel) {
        selectedConfidence = selectedConfidence.toMutableSet().also {
            if (level in it && it.size > 1) {
                it.remove(level)
            } else {
                it.add(level)
            }
        }
        refresh(recountProjectLines = false)
    }

    fun refresh(recountProjectLines: Boolean = true) {
        if (selectedProjectPath.isBlank()) {
            dashboard = null
            snapshot = FlowMetricSnapshot()
            gitSummary = null
            selectedGitObservation = null
            selectedGitCommit = null
            gitDiffPreview = null
            gitDiffDocument = null
            projectConfig = FlowMetricProjectConfig()
            statusMessage = null
            projectWatchService.stop()
            return
        }
        refreshJob?.cancel()
        isLoading = true
        statusMessage = "Refreshing analytics..."

        val projectPath = selectedProjectPath
        val confidence = selectedConfidence
        val days = lookbackDays
        refreshJob = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    FlowMetricDesktopLogger.info("Refresh started for `$projectPath` (recountProjectLines=$recountProjectLines)")
                    val projectRoot = Path.of(projectPath)
                    val store = FlowMetricStore.projectStore(projectRoot)
                    var loadedSnapshot = FlowMetricSnapshot()
                    val snapshotDuration = measureTimeMillis {
                        loadedSnapshot = store.read()
                    }
                    FlowMetricDesktopLogger.info("Snapshot read completed for `$projectPath` in ${snapshotDuration}ms with ${loadedSnapshot.events.size} events")

                    var projectStats = AnalyticsEngine.ProjectScanStats(
                        totalLines = cachedProjectLines ?: 0,
                        totalFiles = cachedProjectFiles ?: 0,
                    )
                    val scanDuration = measureTimeMillis {
                        projectStats = when {
                            recountProjectLines ||
                                cachedProjectPath != projectPath ||
                                cachedProjectLines == null ||
                                cachedProjectFiles == null -> analyticsEngine.scanProject(projectRoot)
                            else -> projectStats
                        }
                    }
                    FlowMetricDesktopLogger.info(
                        "Project scan completed for `$projectPath` in ${scanDuration}ms with ${projectStats.totalFiles} files and ${projectStats.totalLines} lines",
                    )

                    val from = Instant.now().minus(days.toLong(), ChronoUnit.DAYS).toEpochMilli()
                    lateinit var loadedDashboard: DashboardMetrics
                    val dashboardDuration = measureTimeMillis {
                        loadedDashboard = analyticsEngine.buildDashboard(
                            events = loadedSnapshot.events,
                            filter = AnalyticsFilter(
                                projectPath = projectPath,
                                fromEpochMillis = from,
                                confidence = confidence,
                            ),
                            totalProjectLines = projectStats.totalLines,
                            totalProjectFiles = projectStats.totalFiles,
                        )
                    }
                    FlowMetricDesktopLogger.info("Dashboard build completed for `$projectPath` in ${dashboardDuration}ms")

                    var loadedGitSummary = GitWorkingTreeSummary(available = false, message = "Git summary not loaded.")
                    val gitDuration = measureTimeMillis {
                        loadedGitSummary = gitDiffService.summarize(projectRoot)
                    }
                    FlowMetricDesktopLogger.info(
                        "Git summary completed for `$projectPath` in ${gitDuration}ms with ${loadedGitSummary.changedFilesCount} changed files",
                    )

                    RefreshResult(
                        snapshot = loadedSnapshot,
                        dashboard = loadedDashboard,
                        totalLines = projectStats.totalLines,
                        totalFiles = projectStats.totalFiles,
                        gitSummary = loadedGitSummary,
                    )
                }
            }.onSuccess { result ->
                snapshot = result.snapshot
                dashboard = result.dashboard
                gitSummary = result.gitSummary
                cachedProjectPath = projectPath
                cachedProjectLines = result.totalLines
                cachedProjectFiles = result.totalFiles
                isLoading = false
                statusMessage = if (snapshot.events.isEmpty()) {
                    "No tracked change events found yet."
                } else {
                    "Analytics updated."
                }
                FlowMetricDesktopLogger.info("Refresh finished successfully for `$projectPath`")
            }.onFailure { error ->
                if (error is CancellationException) {
                    FlowMetricDesktopLogger.info("Refresh cancelled for `$projectPath`")
                    return@onFailure
                }
                isLoading = false
                statusMessage = "Refresh failed. See ~/.flowmetric-desktop/desktop.log for details."
                FlowMetricDesktopLogger.error("Refresh failed for `$projectPath`", error)
            }
        }
    }

    fun dispose() {
        projectWatchService.close()
        scope.cancel()
    }

    fun selectGitObservation(observation: GitFileObservation) {
        selectedGitCommit = null
        selectedGitObservation = observation
        val trackedPatch = observation.linePatch
        if (!trackedPatch.isNullOrBlank()) {
            gitDiffPreview = trackedPatch
            gitDiffDocument = gitDiffService.parseSingleFileDiff(
                gitDiffService.decoratePatchForObservation(observation, trackedPatch),
            )
            return
        }

        if (observation.fromTrackedEvents) {
            gitDiffPreview =
                "FlowMetric tracked this change time, but it was recorded before per-change line snapshots were enabled."
            gitDiffDocument = null
            return
        }

        gitDiffPreview = "Loading diff..."
        gitDiffDocument = null

        val projectPath = selectedProjectPath
        scope.launch {
            val diff = withContext(Dispatchers.IO) {
                gitDiffService.diffForFile(Path.of(projectPath), observation.filePath, observation.status)
            }
            if (selectedGitObservation?.id == observation.id) {
                gitDiffPreview = diff
                gitDiffDocument = gitDiffService.parseSingleFileDiff(diff)
            }
        }
    }

    fun selectGitCommit(commit: GitCommitSummary) {
        selectedGitObservation = null
        selectedGitCommit = commit
        gitDiffPreview = "Loading commit diff..."
        gitDiffDocument = null

        val projectPath = selectedProjectPath
        scope.launch {
            val diff = withContext(Dispatchers.IO) {
                gitDiffService.diffForCommit(Path.of(projectPath), commit.hash)
            }
            if (selectedGitCommit?.hash == commit.hash) {
                gitDiffPreview = diff
            }
        }
    }

    fun selectUncommittedChanges() {
        selectedGitCommit = null
        if (selectedGitObservation == null) {
            gitDiffPreview = null
            gitDiffDocument = null
        }
    }

    fun revertSelectedGitHunk(hunkIndex: Int) {
        val projectPath = selectedProjectPath
        val document = gitDiffDocument ?: run {
            statusMessage = "No revertable Git patch is loaded."
            return
        }

        if (projectPath.isBlank()) return
        isGitReverting = true
        statusMessage = "Reverting selected block..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                gitDiffService.revertHunk(Path.of(projectPath), document, hunkIndex)
            }
            isGitReverting = false
            statusMessage = result.message
            if (result.success) {
                selectedGitObservation = null
                gitDiffPreview = null
                gitDiffDocument = null
                refresh(recountProjectLines = false)
            }
        }
    }

    fun revertSelectedGitLine(hunkIndex: Int, lineIndex: Int) {
        val projectPath = selectedProjectPath
        val document = gitDiffDocument ?: run {
            statusMessage = "No revertable Git patch is loaded."
            return
        }

        if (projectPath.isBlank()) return
        isGitReverting = true
        statusMessage = "Reverting selected line..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                gitDiffService.revertLine(Path.of(projectPath), document, hunkIndex, lineIndex)
            }
            isGitReverting = false
            statusMessage = result.message
            if (result.success) {
                selectedGitObservation = null
                gitDiffPreview = null
                gitDiffDocument = null
                refresh(recountProjectLines = false)
            }
        }
    }

    fun revertGitObservation(observation: GitFileObservation) {
        val projectPath = selectedProjectPath
        if (projectPath.isBlank()) return

        isGitReverting = true
        statusMessage = "Reverting selected file..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                gitDiffService.revertObservation(Path.of(projectPath), observation)
            }
            isGitReverting = false
            statusMessage = result.message
            if (result.success) {
                if (selectedGitObservation?.id == observation.id) {
                    selectedGitObservation = null
                    gitDiffPreview = null
                    gitDiffDocument = null
                }
                refresh(recountProjectLines = false)
            }
        }
    }

    fun revertGitObservationGroup(observations: List<GitFileObservation>) {
        val projectPath = selectedProjectPath
        if (projectPath.isBlank()) return

        isGitReverting = true
        statusMessage = "Reverting selected file block..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                gitDiffService.revertObservationGroup(Path.of(projectPath), observations)
            }
            isGitReverting = false
            statusMessage = result.message
            if (result.success) {
                selectedGitObservation = null
                gitDiffPreview = null
                gitDiffDocument = null
                refresh(recountProjectLines = false)
            }
        }
    }

    fun saveProjectConfig(
        ignoredExtensions: Set<String>,
        ignoredPathFragments: List<String>,
    ) {
        val projectPath = selectedProjectPath.ifBlank { projectPathInput.trim() }
        if (projectPath.isBlank()) {
            statusMessage = "Select a project before saving configuration."
            return
        }

        val normalizedConfig = FlowMetricProjectConfig(
            ignoredExtensions = ignoredExtensions
                .map { it.trim().removePrefix(".").lowercase() }
                .filter { it.isNotBlank() }
                .toSet(),
            ignoredPathFragments = ignoredPathFragments
                .map { it.trim() }
                .filter { it.isNotBlank() },
        )

        scope.launch {
            withContext(Dispatchers.IO) {
                val projectRoot = Path.of(projectPath)
                FlowMetricProjectConfigStore.projectConfigStore(projectRoot).write(normalizedConfig)
                externalEventRecorder.resetProject(projectRoot)
                externalEventRecorder.prime(projectRoot)
            }
            projectConfig = normalizedConfig
            cachedProjectLines = null
            cachedProjectFiles = null
            statusMessage = "Configuration saved."
            refresh(recountProjectLines = true)
        }
    }

    fun installCodexTracking() {
        val projectPath = selectedProjectPath.ifBlank { projectPathInput.trim() }
        if (projectPath.isBlank()) {
            statusMessage = "Select a project before installing tracking."
            return
        }

        statusMessage = "Installing Codex tracking..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                trackingInstaller.installCodexTracking(Path.of(projectPath))
            }
            statusMessage = result.message
        }
    }

    private fun startWatchingProject(projectPath: String) {
        if (projectPath.isBlank()) {
            projectWatchService.stop()
            return
        }
        projectWatchService.start(scope, Path.of(projectPath)) { batch ->
            watchRefreshJob?.cancel()
            watchRefreshJob = scope.launch {
                delay(150L)
                FlowMetricDesktopLogger.info(
                    "Watcher received ${batch.changes.size} change(s) for `$projectPath`: " +
                        batch.changes.joinToString(limit = 5) { "${it.kind}:${it.path.fileName}" },
                )
                val recorded = withContext(Dispatchers.IO) {
                    externalEventRecorder.recordChanges(Path.of(projectPath), batch.changes)
                }
                if (!appSettings.refreshAutomaticallyOnWatchedChanges) {
                    statusMessage = when {
                        recorded > 0 -> "Recorded $recorded external change event(s). Auto-refresh is off."
                        batch.hasTrackingDataChanges -> "Tracked events changed on disk. Auto-refresh is off."
                        else -> "Project files changed on disk. Auto-refresh is off."
                    }
                    return@launch
                }
                statusMessage = when {
                    recorded > 0 -> "Recorded $recorded external change event(s). Refreshing analytics..."
                    batch.hasTrackingDataChanges -> "Tracked events changed on disk. Refreshing analytics..."
                    else -> "Project files changed on disk. Refreshing analytics and Git diff..."
                }
                refresh(recountProjectLines = false)
            }
        }
    }
}

private val ProjectChangeBatch.hasTrackingDataChanges: Boolean
    get() = changes.any { it.category == ProjectChangeCategory.TRACKING_DATA }

private data class RefreshResult(
    val snapshot: FlowMetricSnapshot,
    val dashboard: DashboardMetrics,
    val totalLines: Int,
    val totalFiles: Int,
    val gitSummary: GitWorkingTreeSummary,
)
