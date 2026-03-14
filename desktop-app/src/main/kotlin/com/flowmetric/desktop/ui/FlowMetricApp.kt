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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowmetric.desktop.viewmodel.FlowMetricViewModel
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
    MaterialTheme {
        var selectedEventFile by remember { mutableStateOf<FileEstimate?>(null) }
        var selectedTab by remember { mutableStateOf(AnalyticsTab.GIT) }
        var configDialogOpen by remember { mutableStateOf(false) }
        val dashboard = viewModel.dashboard

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF2EFE8))
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
                    onConfigure = { configDialogOpen = true },
                    isLoading = viewModel.isLoading,
                    statusMessage = viewModel.statusMessage,
                    configurationEnabled = viewModel.projectPathInput.isNotBlank(),
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
                            ChangedFilesSection(dashboard.files) { selectedEventFile = it }
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
                )
            }
        }

        if (configDialogOpen) {
            ProjectConfigDialog(
                config = viewModel.projectConfig,
                projectPath = viewModel.selectedProjectPath.ifBlank { viewModel.projectPathInput.trim() },
                onDismiss = { configDialogOpen = false },
                onSave = { ignoredExtensions, ignoredPathFragments ->
                    viewModel.saveProjectConfig(ignoredExtensions, ignoredPathFragments)
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
                    color = Color(0xFF49616D),
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
        Text("Global Analytics", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard("Total LOC", totalProjectLines.toString(), Color(0xFF172A3A))
            SummaryCard("Source Files", totalProjectFiles.toString(), Color(0xFF2A7F62))
            SummaryCard("Tracked Events", trackedEvents.toString(), Color(0xFFD96C2F))
            SummaryCard("Sessions", trackedSessions.toString(), Color(0xFF7A3E65))
            SummaryCard("Git Files", gitChangedFiles.toString(), Color(0xFF49616D))
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
            Text("Events Tracking Required", fontWeight = FontWeight.SemiBold)
            Text(
                "If `.flowmetric/events.json` has not been created yet, you will not see event-based line change analytics here.",
                color = Color(0xFF49616D),
            )
            Text(
                "Install and run the FlowMetric extension in your IDE, select the tracked project, and save or edit files so FlowMetric can record line changes.",
                color = Color(0xFF49616D),
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
            Text("Filters", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 7, 30).forEach { days ->
                    val active = days == lookbackDays
                    Box(
                        modifier = Modifier
                            .background(if (active) Color(0xFFD96C2F) else Color(0xFFE8E2D6), RoundedCornerShape(999.dp))
                            .clickable { onDaysSelected(days) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text("$days d", color = if (active) Color.White else Color(0xFF172A3A))
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
        Text("Estimated contribution mix", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard("Estimated AI LOC", estimatedAiLines.toString(), Color(0xFFD96C2F))
            SummaryCard("Estimated Non-AI LOC", estimatedNonAiLines.toString(), Color(0xFF2A7F62))
            SummaryCard("Estimated AI %", "$aiPercentage%", Color(0xFF7A3E65))
        }
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AI vs non-AI estimate")
                Row(modifier = Modifier.fillMaxWidth().height(22.dp).background(Color(0xFFE4DDD1), RoundedCornerShape(999.dp))) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((aiPercentage / 100.0).toFloat().coerceIn(0f, 1f))
                            .background(Color(0xFFD96C2F), RoundedCornerShape(999.dp)),
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
            Text(title, color = Color(0xFF49616D))
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TrendSection(points: List<com.flowmetric.shared.model.TrendPoint>) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Trend", fontWeight = FontWeight.SemiBold)
            if (points.isEmpty()) {
                Text("No tracked changes in the selected range.")
            } else {
                points.forEach { point ->
                    val total = (point.estimatedAiLines + point.estimatedNonAiLines).coerceAtLeast(1)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(point.dayLabel)
                        Row(modifier = Modifier.fillMaxWidth().height(16.dp).background(Color(0xFFE8E2D6), RoundedCornerShape(999.dp))) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth((point.estimatedAiLines.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                                    .background(Color(0xFFD96C2F), RoundedCornerShape(999.dp)),
                            )
                        }
                        Text("Estimated AI ${point.estimatedAiLines} | Estimated non-AI ${point.estimatedNonAiLines}", color = Color(0xFF49616D))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangedFilesSection(files: List<FileEstimate>, onSelect: (FileEstimate) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().height(360.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Changed files", fontWeight = FontWeight.SemiBold)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files) { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF7F4EE), RoundedCornerShape(14.dp))
                            .clickable { onSelect(file) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(File(file.filePath).name, fontWeight = FontWeight.Medium)
                            Text(file.filePath, color = Color(0xFF6B7B83), fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${file.changedLines} changed")
                            Text(classificationLabel(file.classification), color = classificationColor(file.classification))
                            Text("Confidence: ${file.confidence.name.lowercase()}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailPanel(modifier: Modifier, selectedFile: FileEstimate?) {
    Card(modifier = modifier, shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Review", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "This panel is structured to support future revert flows. For v1 it focuses on inspection, confidence, and suspicious change review.",
                color = Color(0xFF49616D),
            )
            if (selectedFile == null) {
                Text("Select a file to inspect its estimated AI/non-AI contribution.")
            } else {
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm")
                Text(File(selectedFile.filePath).name, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Text(selectedFile.filePath, color = Color(0xFF49616D))
                Text("Latest tracked change: ${formatter.format(Date(selectedFile.latestTimestampEpochMillis))}")
                Text("Changed lines: ${selectedFile.changedLines}")
                Text("Estimated AI-generated lines: ${selectedFile.estimatedAiLines}")
                Text("Estimated non-AI lines: ${selectedFile.estimatedNonAiLines}")
                Text("Likely status: ${classificationLabel(selectedFile.classification)}")
                Text("Confidence: ${selectedFile.confidence.name.lowercase().replaceFirstChar(Char::uppercase)}")
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
            Text("No project loaded", fontWeight = FontWeight.SemiBold)
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
    ChangeClassification.ESTIMATED_AI_GENERATED -> Color(0xFFD96C2F)
    ChangeClassification.ESTIMATED_NON_AI -> Color(0xFF2A7F62)
    ChangeClassification.MIXED_OR_UNCLEAR -> Color(0xFF7A3E65)
}

private enum class AnalyticsTab(val title: String) {
    GIT("Git"),
    EVENTS("Events"),
}
