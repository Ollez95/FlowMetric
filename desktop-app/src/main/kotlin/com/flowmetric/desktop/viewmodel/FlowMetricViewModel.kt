package com.flowmetric.desktop.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flowmetric.desktop.git.GitDiffService
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

    var lookbackDays by mutableStateOf(7)
        private set

    var snapshot by mutableStateOf(FlowMetricSnapshot())
        private set

    var dashboard by mutableStateOf<DashboardMetrics?>(null)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var statusMessage by mutableStateOf<String?>(null)
        private set

    var gitSummary by mutableStateOf<GitWorkingTreeSummary?>(null)
        private set

    var selectedGitObservation by mutableStateOf<GitFileObservation?>(null)
        private set

    var selectedGitCommit by mutableStateOf<GitCommitSummary?>(null)
        private set

    var gitDiffPreview by mutableStateOf<String?>(null)
        private set

    var projectConfig by mutableStateOf(FlowMetricProjectConfig())
        private set

    var recentProjects by mutableStateOf(emptyList<String>())
        private set

    init {
        recentProjects = recentProjectsStore.read()
        recentProjects.firstOrNull()?.let { recentProject ->
            if (runCatching { Path.of(recentProject) }.getOrNull()?.toFile()?.exists() == true) {
                setProjectPath(recentProject)
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
            projectConfig = FlowMetricProjectConfig()
            isLoading = false
            statusMessage = "Recent project removed."
            projectWatchService.stop()
        }
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
            val result = withContext(Dispatchers.IO) {
                val store = FlowMetricStore.projectStore(Path.of(projectPath))
                val loadedSnapshot = store.read()
                val projectStats = when {
                    recountProjectLines ||
                        cachedProjectPath != projectPath ||
                        cachedProjectLines == null ||
                        cachedProjectFiles == null -> analyticsEngine.scanProject(Path.of(projectPath))
                    else -> AnalyticsEngine.ProjectScanStats(
                        totalLines = cachedProjectLines ?: 0,
                        totalFiles = cachedProjectFiles ?: 0,
                    )
                }
                val from = Instant.now().minus(days.toLong(), ChronoUnit.DAYS).toEpochMilli()
                val loadedDashboard = analyticsEngine.buildDashboard(
                    events = loadedSnapshot.events,
                    filter = AnalyticsFilter(
                        projectPath = projectPath,
                        fromEpochMillis = from,
                        confidence = confidence,
                    ),
                    totalProjectLines = projectStats.totalLines,
                    totalProjectFiles = projectStats.totalFiles,
                )
                val loadedGitSummary = gitDiffService.summarize(Path.of(projectPath))
                RefreshResult(
                    snapshot = loadedSnapshot,
                    dashboard = loadedDashboard,
                    totalLines = projectStats.totalLines,
                    totalFiles = projectStats.totalFiles,
                    gitSummary = loadedGitSummary,
                )
            }

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
        }
    }

    fun dispose() {
        projectWatchService.close()
        scope.cancel()
    }

    fun selectGitObservation(observation: GitFileObservation) {
        selectedGitCommit = null
        selectedGitObservation = observation
        if (!observation.linePatch.isNullOrBlank()) {
            gitDiffPreview = observation.linePatch
            return
        }

        if (observation.fromTrackedEvents) {
            gitDiffPreview =
                "FlowMetric tracked this change time, but it was recorded before per-change line snapshots were enabled."
            return
        }

        gitDiffPreview = "Loading diff..."

        val projectPath = selectedProjectPath
        scope.launch {
            val diff = withContext(Dispatchers.IO) {
                gitDiffService.diffForFile(Path.of(projectPath), observation.filePath, observation.status)
            }
            if (selectedGitObservation?.id == observation.id) {
                gitDiffPreview = diff
            }
        }
    }

    fun selectGitCommit(commit: GitCommitSummary) {
        selectedGitObservation = null
        selectedGitCommit = commit
        gitDiffPreview = "Loading commit diff..."

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

    private fun startWatchingProject(projectPath: String) {
        if (projectPath.isBlank()) {
            projectWatchService.stop()
            return
        }
        projectWatchService.start(scope, Path.of(projectPath)) { batch ->
            watchRefreshJob?.cancel()
            watchRefreshJob = scope.launch {
                delay(150L)
                val recorded = withContext(Dispatchers.IO) {
                    externalEventRecorder.recordChanges(Path.of(projectPath), batch.changes)
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
