package com.flowmetric.desktop.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flowmetric.desktop.git.GitDiffService
import com.flowmetric.desktop.watch.ProjectChange
import com.flowmetric.desktop.watch.ProjectWatchService
import com.flowmetric.shared.analytics.AnalyticsEngine
import com.flowmetric.shared.model.AnalyticsFilter
import com.flowmetric.shared.model.ConfidenceLevel
import com.flowmetric.shared.model.DashboardMetrics
import com.flowmetric.shared.model.FlowMetricSnapshot
import com.flowmetric.shared.model.GitWorkingTreeSummary
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private var refreshJob: Job? = null
    private var watchRefreshJob: Job? = null
    private var cachedProjectLines: Int? = null
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

    fun updateProjectPathInput(path: String) {
        projectPathInput = path
    }

    fun setProjectPath(path: String) {
        val normalizedPath = path.trim()
        selectedProjectPath = normalizedPath
        projectPathInput = normalizedPath
        cachedProjectPath = null
        cachedProjectLines = null
        startWatchingProject(normalizedPath)
        refresh(recountProjectLines = true)
    }

    fun applySelectedProjectPathOrRefresh() {
        val normalizedPath = projectPathInput.trim()
        if (normalizedPath.isBlank()) {
            selectedProjectPath = ""
            dashboard = null
            snapshot = FlowMetricSnapshot()
            gitSummary = null
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
                val totalLines = when {
                    recountProjectLines || cachedProjectPath != projectPath || cachedProjectLines == null ->
                        analyticsEngine.countProjectLines(Path.of(projectPath))
                    else -> cachedProjectLines ?: 0
                }
                val from = Instant.now().minus(days.toLong(), ChronoUnit.DAYS).toEpochMilli()
                val loadedDashboard = analyticsEngine.buildDashboard(
                    events = loadedSnapshot.events,
                    filter = AnalyticsFilter(
                        projectPath = projectPath,
                        fromEpochMillis = from,
                        confidence = confidence,
                    ),
                    totalProjectLines = totalLines,
                )
                val loadedGitSummary = gitDiffService.summarize(Path.of(projectPath))
                RefreshResult(
                    snapshot = loadedSnapshot,
                    dashboard = loadedDashboard,
                    totalLines = totalLines,
                    gitSummary = loadedGitSummary,
                )
            }

            snapshot = result.snapshot
            dashboard = result.dashboard
            gitSummary = result.gitSummary
            cachedProjectPath = projectPath
            cachedProjectLines = result.totalLines
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

    private fun startWatchingProject(projectPath: String) {
        if (projectPath.isBlank()) {
            projectWatchService.stop()
            return
        }
        projectWatchService.start(scope, Path.of(projectPath)) { change ->
            watchRefreshJob?.cancel()
            watchRefreshJob = scope.launch {
                delay(150L)
                statusMessage = when (change) {
                    ProjectChange.TRACKING_DATA -> "Tracked events changed on disk. Refreshing analytics..."
                    ProjectChange.PROJECT_FILES -> "Project files changed on disk. Refreshing analytics and Git diff..."
                }
                refresh(recountProjectLines = false)
            }
        }
    }
}

private data class RefreshResult(
    val snapshot: FlowMetricSnapshot,
    val dashboard: DashboardMetrics,
    val totalLines: Int,
    val gitSummary: GitWorkingTreeSummary,
)
