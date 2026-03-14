package com.flowmetric.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowmetric.desktop.viewmodel.FlowMetricViewModel
import com.flowmetric.shared.model.ChangeClassification
import com.flowmetric.shared.model.ConfidenceLevel
import com.flowmetric.shared.model.FileEstimate
import com.flowmetric.shared.model.FlowMetricProjectConfig
import com.flowmetric.shared.model.GitFileObservation
import com.flowmetric.shared.model.GitFileStatus
import com.flowmetric.shared.model.GitWorkingTreeSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JFileChooser
import androidx.compose.ui.window.DialogWindow

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
                if (dashboard != null) {
                    when (selectedTab) {
                        AnalyticsTab.GIT -> GitSection(viewModel.gitSummary, viewModel::selectGitObservation)
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
                    selectedObservation = viewModel.selectedGitObservation,
                    diffPreview = viewModel.gitDiffPreview,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectHeader(
    projectPath: String,
    recentProjects: List<String>,
    onPathChanged: (String) -> Unit,
    onRecentProjectSelected: (String) -> Unit,
    onRecentProjectRemoved: (String) -> Unit,
    onBrowse: () -> Unit,
    onRefresh: () -> Unit,
    onConfigure: () -> Unit,
    isLoading: Boolean,
    statusMessage: String?,
    configurationEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("FlowMetric", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color(0xFF172A3A))
        Text(
            "Estimate whether recent project changes were likely AI-assisted. These metrics are directional, not exact attribution.",
            color = Color(0xFF49616D),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = projectPath,
                onValueChange = onPathChanged,
                modifier = Modifier.weight(1f),
                label = { Text("Selected project") },
            )
            OutlinedButton(onClick = onBrowse) { Text("Browse") }
            OutlinedButton(onClick = onConfigure, enabled = configurationEnabled) { Text("Config") }
            Button(onClick = onRefresh, enabled = !isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Refresh")
                }
            }
        }
        statusMessage?.let { Text(it, color = Color(0xFF6B7B83), fontSize = 13.sp) }
        if (recentProjects.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recent Projects", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF172A3A))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    recentProjects.take(6).forEach { path ->
                        RecentProjectChip(
                            path = path,
                            isActive = path == projectPath,
                            onOpen = { onRecentProjectSelected(path) },
                            onRemove = { onRecentProjectRemoved(path) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentProjectChip(
    path: String,
    isActive: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val projectFile = File(path)
    val projectName = projectFile.name.ifBlank { path }
    val parentName = projectFile.parentFile?.name?.takeIf { it.isNotBlank() }

    Card(
        modifier = Modifier
            .width(300.dp)
            .clickable(onClick = onOpen)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) Color(0xFFD96C2F) else Color(0xFFE1D8C8),
                shape = RoundedCornerShape(20.dp),
            ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .background(if (isActive) Color(0xFFF6E8DC) else Color(0xFFF9F6F0))
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        projectName,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF172A3A),
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFD96C2F), RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text("Active", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                parentName?.let {
                    Text(it, fontSize = 12.sp, color = Color(0xFF7A867D))
                }
                Text(
                    path,
                    fontSize = 12.sp,
                    color = Color(0xFF6B7B83),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Open project",
                    fontSize = 12.sp,
                    color = if (isActive) Color(0xFFD96C2F) else Color(0xFF49616D),
                    fontWeight = FontWeight.Medium,
                )
            }
            Box(
                modifier = Modifier
                    .background(Color(0xFFF1E8DA), RoundedCornerShape(999.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Remove", color = Color(0xFF6B7B83), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ProjectConfigDialog(
    config: FlowMetricProjectConfig,
    projectPath: String,
    onDismiss: () -> Unit,
    onSave: (Set<String>, List<String>) -> Unit,
) {
    var ignoredExtensionsInput by remember(config) {
        mutableStateOf(config.ignoredExtensions.sorted().joinToString(", "))
    }
    var ignoredPathFragmentsInput by remember(config) {
        mutableStateOf(config.ignoredPathFragments.joinToString("\n"))
    }

    LaunchedEffect(config) {
        ignoredExtensionsInput = config.ignoredExtensions.sorted().joinToString(", ")
        ignoredPathFragmentsInput = config.ignoredPathFragments.joinToString("\n")
    }

    DialogWindow(onCloseRequest = onDismiss, title = "FlowMetric Configuration") {
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(
                modifier = Modifier.padding(20.dp).width(620.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Project Configuration", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (projectPath.isBlank()) "Choose a project first to save config."
                    else "Settings are stored in `$projectPath/.flowmetric/config.json`.",
                    color = Color(0xFF49616D),
                )
                OutlinedTextField(
                    value = ignoredExtensionsInput,
                    onValueChange = { ignoredExtensionsInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ignored extensions") },
                    supportingText = { Text("Comma-separated values like `md, json, log`.") },
                )
                OutlinedTextField(
                    value = ignoredPathFragmentsInput,
                    onValueChange = { ignoredPathFragmentsInput = it },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    label = { Text("Ignored path fragments") },
                    supportingText = { Text("One per line, for example `/generated/` or `/docs/`.") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            onSave(
                                ignoredExtensionsInput.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet(),
                                ignoredPathFragmentsInput.lines().map { it.trim() }.filter { it.isNotBlank() },
                            )
                        },
                        enabled = projectPath.isNotBlank(),
                    ) {
                        Text("Save")
                    }
                }
            }
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
private fun RowScope.SummaryCard(title: String, value: String, accent: Color) {
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
private fun GitSection(summary: GitWorkingTreeSummary?, onSelect: (GitFileObservation) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Git working tree", fontWeight = FontWeight.SemiBold)
            when {
                summary == null -> Text("Git diff has not been loaded yet.")
                !summary.available -> Text(summary.message ?: "Git is not available for this folder.")
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SummaryCard("Git files", summary.changedFilesCount.toString(), Color(0xFF172A3A))
                        SummaryCard("Git +lines", summary.totalInsertedLines.toString(), Color(0xFF2A7F62))
                        SummaryCard("Git -lines", summary.totalDeletedLines.toString(), Color(0xFFD96C2F))
                    }
                    if (summary.observations.isEmpty()) {
                        Text(summary.message ?: "Working tree is clean.")
                    } else {
                        val timelineColors = listOf(
                            Color(0xFFD96C2F),
                            Color(0xFF2A7F62),
                            Color(0xFF49616D),
                            Color(0xFF7A3E65),
                            Color(0xFFB7791F),
                        )
                        observationGroups(summary.observations.take(16)).forEachIndexed { index, group ->
                            val accent = timelineColors[index % timelineColors.size]
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(accent.copy(alpha = 0.08f))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "Changed around ${formatTimestamp(group.first().observedAtEpochMillis)}",
                                        color = accent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    group.forEach { observation ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFFF7F4EE), RoundedCornerShape(14.dp))
                                                .clickable { onSelect(observation) }
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(observation.filePath, fontWeight = FontWeight.Medium)
                                                Text(
                                                    gitStatusLabel(observation.status),
                                                    color = gitStatusColor(observation.status),
                                                    fontSize = 12.sp,
                                                )
                                                Text(
                                                    "Changed: ${formatTimestamp(observation.observedAtEpochMillis)}",
                                                    color = Color(0xFF6B7B83),
                                                    fontSize = 12.sp,
                                                )
                                            }
                                            Text("+${observation.insertedLines} / -${observation.deletedLines}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitDetailPanel(
    modifier: Modifier,
    selectedObservation: GitFileObservation?,
    diffPreview: String?,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Git Review", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "This tab is a plain Git working-tree view. It shows changed files, timestamps, and line deltas without any AI classification.",
                color = Color(0xFF49616D),
            )
            if (selectedObservation == null) {
                Text("Select a Git timeline entry to inspect its timestamp and diff summary.")
            } else {
                Text(File(selectedObservation.filePath).name, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Text(selectedObservation.filePath, color = Color(0xFF49616D))
                Text("Git status: ${gitStatusLabel(selectedObservation.status)}")
                Text("Inserted lines: ${selectedObservation.insertedLines}")
                Text("Deleted lines: ${selectedObservation.deletedLines}")
                Text("Changed at: ${formatTimestamp(selectedObservation.observedAtEpochMillis)}")
                selectedObservation.fileModifiedEpochMillis?.let {
                    Text("File modified: ${formatTimestamp(it)}")
                }
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("How to read this", fontWeight = FontWeight.SemiBold)
                        Text("If this entry comes from tracked edit history, the diff below shows only the lines changed in that time bucket. Otherwise FlowMetric falls back to the current Git diff for the file.")
                    }
                }
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Changed lines", fontWeight = FontWeight.SemiBold)
                        GitDiffPreview(
                            diffPreview = diffPreview,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GitDiffPreview(diffPreview: String?, modifier: Modifier = Modifier) {
    val diffText = diffPreview ?: "Select a Git timeline entry to load its diff."
    Column(
        modifier = modifier
            .background(Color(0xFFFBF8F2), RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        diffText.lines().forEach { line ->
            Text(
                text = line.ifEmpty { " " },
                color = gitDiffLineColor(line),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
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

private fun gitStatusLabel(status: GitFileStatus): String = when (status) {
    GitFileStatus.MODIFIED -> "Modified"
    GitFileStatus.ADDED -> "Added"
    GitFileStatus.DELETED -> "Deleted"
    GitFileStatus.RENAMED -> "Renamed"
    GitFileStatus.UNTRACKED -> "Untracked"
    GitFileStatus.TYPE_CHANGED -> "Type changed"
    GitFileStatus.UNKNOWN -> "Unknown"
}

private fun gitStatusColor(status: GitFileStatus): Color = when (status) {
    GitFileStatus.ADDED, GitFileStatus.UNTRACKED -> Color(0xFF2A7F62)
    GitFileStatus.DELETED -> Color(0xFFD96C2F)
    GitFileStatus.RENAMED, GitFileStatus.TYPE_CHANGED -> Color(0xFF7A3E65)
    GitFileStatus.MODIFIED, GitFileStatus.UNKNOWN -> Color(0xFF49616D)
}

private fun gitDiffLineColor(line: String): Color = when {
    line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff --git") -> Color(0xFF7A3E65)
    line.startsWith("@@") -> Color(0xFFB7791F)
    line.startsWith("+") -> Color(0xFF2A7F62)
    line.startsWith("-") -> Color(0xFFD96C2F)
    else -> Color(0xFF49616D)
}

private fun observationGroups(observations: List<GitFileObservation>): List<List<GitFileObservation>> {
    val sorted = observations.sortedByDescending { it.observedAtEpochMillis }
    if (sorted.isEmpty()) return emptyList()

    val groups = mutableListOf<MutableList<GitFileObservation>>()
    var currentGroup = mutableListOf<GitFileObservation>()
    var previousTimestamp: Long? = null

    sorted.forEach { observation ->
        val timestamp = observation.observedAtEpochMillis
        if (previousTimestamp != null && previousTimestamp - timestamp > 2 * 60 * 1000L) {
            groups += currentGroup
            currentGroup = mutableListOf()
        }
        currentGroup += observation
        previousTimestamp = timestamp
    }
    if (currentGroup.isNotEmpty()) groups += currentGroup

    return groups
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(epochMillis))

private enum class AnalyticsTab(val title: String) {
    GIT("Git"),
    EVENTS("Events"),
}
