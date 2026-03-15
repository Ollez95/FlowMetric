package com.flowmetric.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowmetric.desktop.viewmodel.FlowMetricViewModel
import com.flowmetric.shared.model.ChangeEvent
import com.flowmetric.shared.model.ChangeClassification
import com.flowmetric.shared.model.ConfidenceLevel
import com.flowmetric.shared.model.FileEstimate
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JFileChooser

@Composable
fun FlowMetricApp(viewModel: FlowMetricViewModel = remember { FlowMetricViewModel() }) {
    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }
    FlowMetricTheme(themePreference = viewModel.appSettings.theme) {
        var selectedEventFile by remember { mutableStateOf<FileEstimate?>(null) }
        var selectedEvent by remember { mutableStateOf<ChangeEvent?>(null) }
        var selectedTab by remember { mutableStateOf(viewModel.appSettings.defaultTab.toAnalyticsTab()) }
        var configDialogOpen by remember { mutableStateOf(false) }
        val dashboard = viewModel.dashboard
        val filteredEventHistory = remember(
            viewModel.snapshot.events,
            viewModel.selectedProjectPath,
            viewModel.lookbackDays,
            viewModel.selectedConfidence,
        ) {
            val projectPath = viewModel.selectedProjectPath
            val from = System.currentTimeMillis() - (viewModel.lookbackDays * 24L * 60L * 60L * 1000L)
            viewModel.snapshot.events
                .asSequence()
                .filter { it.projectPath == projectPath }
                .filter { it.timestampEpochMillis >= from }
                .filter { it.snapshot.confidence in viewModel.selectedConfidence }
                .sortedByDescending { it.timestampEpochMillis }
                .toList()
        }
        LaunchedEffect(viewModel.appSettings.defaultTab) {
            selectedTab = viewModel.appSettings.defaultTab.toAnalyticsTab()
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                modifier = Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProjectHeader(
                    projectPath = viewModel.projectPathInput,
                    recentProjects = viewModel.recentProjects,
                    onPathChanged = viewModel::updateProjectPathInput,
                    onRecentProjectSelected = viewModel::setProjectPath,
                    onRecentProjectRemoved = viewModel::removeRecentProject,
                    onRecentProjectsReordered = viewModel::reorderRecentProjects,
                    onBrowse = {
                        val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            viewModel.setProjectPath(chooser.selectedFile.absolutePath)
                        }
                    },
                    onRefresh = viewModel::applySelectedProjectPathOrRefresh,
                    onConfigure = {
                        viewModel.refreshErrorLogs()
                        configDialogOpen = true
                    },
                    onInstallTracking = viewModel::installCodexTracking,
                    isLoading = viewModel.isLoading,
                    statusMessage = viewModel.statusMessage,
                )
                FilterPanel(
                    lookbackDays = viewModel.lookbackDays,
                    selectedConfidence = viewModel.selectedConfidence,
                    onDaysSelected = viewModel::updateLookbackDays,
                    onConfidenceToggled = viewModel::toggleConfidence,
                )
                dashboard?.let {
                    GlobalAnalyticsSection(
                        totalProjectLines = it.totalProjectLines,
                        totalProjectFiles = it.totalProjectFiles,
                        trackedEvents = viewModel.snapshot.events.size,
                        trackedSessions = it.sessions.size,
                        gitChangedFiles = viewModel.gitSummary?.changedFilesCount ?: 0,
                    )
                }
                AnalyticsTabs(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
                if (viewModel.isLoading && dashboard == null && viewModel.selectedProjectPath.isNotBlank()) {
                    ProjectLoadingState()
                } else if (dashboard != null) {
                    when (selectedTab) {
                        AnalyticsTab.GIT -> GitSection(
                            summary = viewModel.gitSummary,
                            selectedCommit = viewModel.selectedGitCommit,
                            isReverting = viewModel.isGitReverting,
                            onSelectObservation = viewModel::selectGitObservation,
                            onSelectCommit = viewModel::selectGitCommit,
                            onSelectUncommitted = viewModel::selectUncommittedChanges,
                            onRevertObservation = viewModel::revertGitObservation,
                            onRevertObservationGroup = viewModel::revertGitObservationGroup,
                        )
                        AnalyticsTab.EVENTS -> {
                            if (viewModel.snapshot.events.isEmpty()) {
                                EventsSetupNotice()
                            }
                            SummarySection(dashboard.estimatedAiLines, dashboard.estimatedNonAiLines, dashboard.aiPercentage, dashboard.nonAiPercentage)
                            TrendSection(dashboard.trends)
                            EventsSection(
                                files = dashboard.files,
                                events = filteredEventHistory,
                                onSelectEvent = { event, file ->
                                    selectedEvent = event
                                    selectedEventFile = file ?: dashboard.files.firstOrNull { it.filePath == event.filePath }
                                },
                            )
                        }
                    }
                } else {
                    EmptyState()
                }
            }

            when (selectedTab) {
                AnalyticsTab.GIT -> GitDetailPanel(
                    modifier = Modifier.weight(0.9f).fillMaxHeight(),
                    selectedCommit = viewModel.selectedGitCommit,
                    selectedObservation = viewModel.selectedGitObservation,
                    diffPreview = viewModel.gitDiffPreview,
                    diffDocument = viewModel.gitDiffDocument,
                    isReverting = viewModel.isGitReverting,
                    onRevertHunk = viewModel::revertSelectedGitHunk,
                    onRevertLine = viewModel::revertSelectedGitLine,
                )
                AnalyticsTab.EVENTS -> DetailPanel(
                    modifier = Modifier.weight(0.9f).fillMaxHeight(),
                    selectedFile = selectedEventFile,
                    selectedEvent = selectedEvent,
                )
            }
        }

        if (configDialogOpen) {
            SettingsDialog(
                appSettings = viewModel.appSettings,
                config = viewModel.projectConfig,
                projectPath = viewModel.selectedProjectPath.ifBlank { viewModel.projectPathInput.trim() },
                errorLogPath = viewModel.desktopLogPath,
                errorLogs = viewModel.errorLogs,
                onRefreshErrors = viewModel::refreshErrorLogs,
                onDeleteError = viewModel::deleteErrorLog,
                onClearErrors = viewModel::clearErrorLogs,
                onDismiss = { configDialogOpen = false },
                onSave = { appSettings, ignoredExtensions, ignoredPathFragments ->
                    val projectPath = viewModel.selectedProjectPath.ifBlank { viewModel.projectPathInput.trim() }
                    viewModel.saveAppSettings(appSettings)
                    if (projectPath.isNotBlank()) {
                        viewModel.saveProjectConfig(ignoredExtensions, ignoredPathFragments)
                    }
                    configDialogOpen = false
                },
            )
        }
    }
}

@Composable
private fun ProjectLoadingState() {
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Loading project", fontWeight = FontWeight.SemiBold)
                Text(
                    "Refreshing metrics, Git data, and tracked history for the selected project.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GlobalAnalyticsSection(
    totalProjectLines: Int,
    totalProjectFiles: Int,
    trackedEvents: Int,
    trackedSessions: Int,
    gitChangedFiles: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Global Analytics", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard("Total LOC", totalProjectLines.toString(), FlowMetricInk)
            SummaryCard("Source Files", totalProjectFiles.toString(), FlowMetricTeal)
            SummaryCard("Tracked Events", trackedEvents.toString(), FlowMetricOrange)
            SummaryCard("Sessions", trackedSessions.toString(), FlowMetricPlum)
            SummaryCard("Git Files", gitChangedFiles.toString(), FlowMetricSlate)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalyticsTabs(
    selectedTab: AnalyticsTab,
    onTabSelected: (AnalyticsTab) -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            AnalyticsTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selectedTab,
                    onClick = { onTabSelected(tab) },
                    text = { Text(tab.title) },
                )
            }
        }
    }
}

@Composable
private fun EventsSetupNotice() {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Events Tracking Required", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "If `.flowmetric/events.json` has not been created yet, you will not see event-based line change analytics here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Install and run the FlowMetric extension in your IDE, select the tracked project, and save or edit files so FlowMetric can record line changes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilterPanel(
    lookbackDays: Int,
    selectedConfidence: Set<ConfidenceLevel>,
    onDaysSelected: (Int) -> Unit,
    onConfidenceToggled: (ConfidenceLevel) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Filters", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 7, 14, 30).forEach { days ->
                    val active = days == lookbackDays
                    Box(
                        modifier = Modifier
                            .background(
                                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(999.dp),
                            )
                            .clickable { onDaysSelected(days) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            "$days d",
                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfidenceLevel.entries.forEach { level ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = level in selectedConfidence, onCheckedChange = { onConfidenceToggled(level) })
                        Text(level.name.lowercase().replaceFirstChar(Char::uppercase))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummarySection(
    estimatedAiLines: Int,
    estimatedNonAiLines: Int,
    aiPercentage: Double,
    nonAiPercentage: Double,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Estimated contribution mix", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard("Estimated AI LOC", estimatedAiLines.toString(), FlowMetricOrange)
            SummaryCard("Estimated Non-AI LOC", estimatedNonAiLines.toString(), FlowMetricTeal)
            SummaryCard("Estimated AI %", "$aiPercentage%", FlowMetricPlum)
        }
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AI vs non-AI estimate")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((aiPercentage / 100.0).toFloat().coerceIn(0f, 1f))
                            .background(FlowMetricOrange, RoundedCornerShape(999.dp)),
                    )
                }
                Text("Estimated AI-assisted: $aiPercentage%  |  Estimated non-AI: $nonAiPercentage%")
            }
        }
    }
}

@Composable
internal fun RowScope.SummaryCard(title: String, value: String, accent: Color) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(14.dp).background(accent, RoundedCornerShape(999.dp)))
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TrendSection(points: List<com.flowmetric.shared.model.TrendPoint>) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Trend", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (points.isEmpty()) {
                Text("No tracked changes in the selected range.")
            } else {
                points.forEach { point ->
                    val total = (point.estimatedAiLines + point.estimatedNonAiLines).coerceAtLeast(1)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(point.dayLabel)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth((point.estimatedAiLines.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                                    .background(FlowMetricOrange, RoundedCornerShape(999.dp)),
                            )
                        }
                        Text(
                            "Estimated AI ${point.estimatedAiLines} | Estimated non-AI ${point.estimatedNonAiLines}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventsSection(
    files: List<FileEstimate>,
    events: List<ChangeEvent>,
    onSelectEvent: (ChangeEvent, FileEstimate?) -> Unit,
) {
    val groupedEvents = remember(events) { eventHistoryGroups(events) }
    val pageCount = groupedEvents.pageCount(EVENT_HISTORY_GROUPS_PER_PAGE)
    var pageIndex by remember(events) { mutableStateOf(0) }
    val safePageIndex = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val visibleGroups = groupedEvents.pageSlice(safePageIndex, EVENT_HISTORY_GROUPS_PER_PAGE)
    val fileByPath = remember(files) { files.associateBy { it.filePath } }
    val timelineColors = listOf(
        FlowMetricOrange,
        FlowMetricTeal,
        FlowMetricSlate,
        FlowMetricPlum,
        FlowMetricGold,
    )

    Text("Tracked events", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
    Text(
        "${events.size} tracked event${if (events.size == 1) "" else "s"} in the selected range",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
    )

    if (groupedEvents.isEmpty()) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No tracked history in the selected range.", fontWeight = FontWeight.Medium)
                Text(
                    "Save or edit files while FlowMetric tracking is enabled to see grouped event history here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Page ${safePageIndex + 1} of ${pageCount.coerceAtLeast(1)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pageIndex = (safePageIndex - 1).coerceAtLeast(0) },
                    enabled = safePageIndex > 0,
                ) {
                    Text("Previous")
                }
                Button(
                    onClick = { pageIndex = (safePageIndex + 1).coerceAtMost(pageCount - 1) },
                    enabled = safePageIndex < pageCount - 1,
                ) {
                    Text("Next")
                }
            }
        }
        visibleGroups.forEachIndexed { index, group ->
            val accent = timelineColors[index % timelineColors.size]
            val uniqueFiles = group.map { it.filePath }.distinct()
            val newestTimestamp = group.maxOf { it.timestampEpochMillis }
            val oldestTimestamp = group.minOf { it.timestampEpochMillis }
            Card(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .background(Color.Transparent)
                    .then(Modifier),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(accent.copy(alpha = 0.08f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Changed around ${formatTimestamp(group.first().timestampEpochMillis)}",
                        color = accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "History: ${formatTimestamp(oldestTimestamp)} to ${formatTimestamp(newestTimestamp)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    "${uniqueFiles.size} file${if (uniqueFiles.size == 1) "" else "s"} changed",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Text(
                                "Files: ${uniqueFiles.joinToString(", ") { File(it).name }}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    group.forEach { event ->
                        val fileEstimate = fileByPath[event.filePath]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .clickable { onSelectEvent(event, fileEstimate) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(event.filePath, fontWeight = FontWeight.Medium)
                                Text(
                                    classificationLabel(event.snapshot.classification),
                                    color = classificationColor(event.snapshot.classification),
                                    fontSize = 12.sp,
                                )
                                Text(
                                    "Changed: ${formatTimestamp(event.timestampEpochMillis)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${event.delta.changedLines} changed")
                                Text(
                                    "Confidence: ${event.snapshot.confidence.name.lowercase().replaceFirstChar(Char::uppercase)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailPanel(
    modifier: Modifier,
    selectedFile: FileEstimate?,
    selectedEvent: ChangeEvent?,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Review", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "This panel is structured to support future revert flows. For v1 it focuses on inspection, confidence, and suspicious change review.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedFile == null) {
                Text("Select an event to inspect the exact lines changed in that event.")
            } else {
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm")
                val displayedPath = selectedEvent?.filePath ?: selectedFile.filePath
                Text(File(displayedPath).name, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Text(displayedPath, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (selectedEvent != null) {
                    Text("Selected event: ${formatter.format(Date(selectedEvent.timestampEpochMillis))}")
                    Text("Changed lines in event: ${selectedEvent.delta.changedLines}")
                    Text("Inserted lines: ${selectedEvent.delta.inserted}")
                    Text("Deleted lines: ${selectedEvent.delta.deleted}")
                    Text("Estimated AI-generated lines in event: ${selectedEvent.snapshot.estimatedAiLines}")
                    Text("Estimated non-AI lines in event: ${selectedEvent.snapshot.estimatedNonAiLines}")
                    Text("Likely status: ${classificationLabel(selectedEvent.snapshot.classification)}")
                    Text("Confidence: ${selectedEvent.snapshot.confidence.name.lowercase().replaceFirstChar(Char::uppercase)}")
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Selected event metadata", fontWeight = FontWeight.SemiBold)
                            Text("Observed at: ${formatter.format(Date(selectedEvent.timestampEpochMillis))}")
                            Text("Source: ${selectedEvent.metadata.source.name.lowercase().replace('_', ' ')}")
                            selectedEvent.metadata.sourceLabel?.let { Text("Source label: $it") }
                            selectedEvent.metadata.agentModel?.let { Text("Model: $it") }
                            selectedEvent.metadata.branchName?.let { Text("Branch: $it") }
                            selectedEvent.metadata.headCommitHash?.let { Text("Commit: ${it.take(12)}") }
                            Text("Session event index: ${selectedEvent.metadata.sessionEventIndex}")
                            Text("Files touched in session: ${selectedEvent.metadata.filesTouchedInSession}")
                        }
                    }
                    selectedEvent.metadata.linePatch?.let { linePatch ->
                        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Changed lines in this event", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Estimated AI-generated lines: ${selectedEvent.snapshot.estimatedAiLines} | " +
                                        "Estimated non-AI lines: ${selectedEvent.snapshot.estimatedNonAiLines}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    linePatch,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    Text("Latest tracked change: ${formatter.format(Date(selectedFile.latestTimestampEpochMillis))}")
                    Text("Changed lines in selected range: ${selectedFile.changedLines}")
                    Text("Estimated AI-generated lines in selected range: ${selectedFile.estimatedAiLines}")
                    Text("Estimated non-AI lines in selected range: ${selectedFile.estimatedNonAiLines}")
                    Text("Likely status: ${classificationLabel(selectedFile.classification)}")
                    Text("Confidence: ${selectedFile.confidence.name.lowercase().replaceFirstChar(Char::uppercase)}")
                }
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Prepared for future revert support", fontWeight = FontWeight.SemiBold)
                        Text("v1 stops at review. A future version can attach diff snapshots and safe revert actions to each tracked session.")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No project loaded", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text("Choose a local project that already contains FlowMetric tracking data in `.flowmetric/events.json`.")
        }
    }
}

private fun classificationLabel(classification: ChangeClassification): String = when (classification) {
    ChangeClassification.ESTIMATED_AI_GENERATED -> "Estimated AI-assisted"
    ChangeClassification.ESTIMATED_NON_AI -> "Estimated non-AI"
    ChangeClassification.MIXED_OR_UNCLEAR -> "Mixed / unclear"
}

private fun classificationColor(classification: ChangeClassification): Color = when (classification) {
    ChangeClassification.ESTIMATED_AI_GENERATED -> FlowMetricOrange
    ChangeClassification.ESTIMATED_NON_AI -> FlowMetricTeal
    ChangeClassification.MIXED_OR_UNCLEAR -> FlowMetricPlum
}

private fun eventHistoryGroups(events: List<ChangeEvent>): List<List<ChangeEvent>> =
    events
        .sortedByDescending { it.timestampEpochMillis }
        .groupBy { bucketStart(it.timestampEpochMillis) }
        .values
        .sortedByDescending { group -> group.maxOf { it.timestampEpochMillis } }

private fun <T> List<T>.pageCount(pageSize: Int): Int =
    if (isEmpty()) 0 else ((size - 1) / pageSize) + 1

private fun <T> List<T>.pageSlice(pageIndex: Int, pageSize: Int): List<T> {
    if (isEmpty()) return emptyList()
    val startIndex = (pageIndex * pageSize).coerceAtMost(lastIndex + 1)
    val endIndex = (startIndex + pageSize).coerceAtMost(size)
    return subList(startIndex, endIndex)
}

private fun bucketStart(epochMillis: Long): Long =
    epochMillis - (epochMillis % EVENT_HISTORY_BUCKET_MS)

private enum class AnalyticsTab(val title: String) {
    GIT("Git"),
    EVENTS("Events"),
}

private fun com.flowmetric.desktop.persistence.StartupTabPreference.toAnalyticsTab(): AnalyticsTab = when (this) {
    com.flowmetric.desktop.persistence.StartupTabPreference.GIT -> AnalyticsTab.GIT
    com.flowmetric.desktop.persistence.StartupTabPreference.EVENTS -> AnalyticsTab.EVENTS
}

private const val EVENT_HISTORY_BUCKET_MS = 2 * 60 * 1000L
private const val EVENT_HISTORY_GROUPS_PER_PAGE = 6
