package com.flowmetric.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.flowmetric.desktop.logging.DesktopErrorLogEntry
import com.flowmetric.desktop.persistence.DesktopAppSettings
import com.flowmetric.desktop.persistence.DesktopThemePreference
import com.flowmetric.desktop.persistence.StartupTabPreference
import com.flowmetric.shared.model.FlowMetricProjectConfig
import java.io.File

@Composable
internal fun ProjectHeader(
    projectPath: String,
    recentProjects: List<String>,
    onPathChanged: (String) -> Unit,
    onRecentProjectSelected: (String) -> Unit,
    onRecentProjectRemoved: (String) -> Unit,
    onRecentProjectsReordered: (List<String>) -> Unit,
    onBrowse: () -> Unit,
    onRefresh: () -> Unit,
    onConfigure: () -> Unit,
    onInstallTracking: () -> Unit,
    isLoading: Boolean,
    statusMessage: String?,
) {
    var pendingRemovalPath by remember { mutableStateOf<String?>(null) }
    var orderedProjects by remember { mutableStateOf(recentProjects) }
    var draggedPath by remember { mutableStateOf<String?>(null) }
    var draggedFromIndex by remember { mutableStateOf<Int?>(null) }
    var draggedTargetIndex by remember { mutableStateOf<Int?>(null) }
    var draggedOffsetY by remember { mutableStateOf(0f) }
    val itemHeights = remember { mutableMapOf<String, Float>() }

    LaunchedEffect(recentProjects) {
        orderedProjects = recentProjects
        if (draggedPath !in recentProjects) {
            draggedPath = null
            draggedFromIndex = null
            draggedTargetIndex = null
            draggedOffsetY = 0f
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("FlowMetric", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Estimate whether recent project changes were likely AI-assisted. These metrics are directional, not exact attribution.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = projectPath,
                onValueChange = onPathChanged,
                modifier = Modifier.weight(1f),
                label = { Text("Selected project") },
            )
            ProjectActionButton(
                onClick = onBrowse,
                contentDescription = "Browse for project",
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                    )
                },
            )
            ProjectActionButton(
                onClick = onInstallTracking,
                enabled = projectPath.isNotBlank(),
                contentDescription = "Install tracking",
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Extension,
                        contentDescription = null,
                    )
                },
            )
            FilledTonalIconButton(
                onClick = onRefresh,
                enabled = !isLoading,
                modifier = Modifier.semantics { contentDescription = "Refresh project" },
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                    )
                }
            }
            SettingsLauncherButton(onClick = onConfigure)
        }
        statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
        if (recentProjects.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recent Projects", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                Text("Drag and drop to change the order.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    orderedProjects.forEach { path ->
                        val currentIndex = orderedProjects.indexOf(path)
                        RecentProjectChip(
                            path = path,
                            isActive = path == projectPath,
                            isDragging = draggedPath == path,
                            isDropTarget = draggedPath != null && draggedPath != path && draggedTargetIndex == currentIndex,
                            dragOffsetY = if (draggedPath == path) draggedOffsetY else 0f,
                            onHeightChanged = { height -> itemHeights[path] = height },
                            onOpen = { onRecentProjectSelected(path) },
                            onRemove = { pendingRemovalPath = path },
                            onDragStart = {
                                draggedPath = path
                                draggedFromIndex = currentIndex
                                draggedTargetIndex = currentIndex
                                draggedOffsetY = 0f
                            },
                            onDrag = { deltaY ->
                                if (draggedPath != path) return@RecentProjectChip
                                draggedOffsetY += deltaY
                                draggedTargetIndex = draggedFromIndex?.let { fromIndex ->
                                    calculateDropTargetIndex(
                                        sourceIndex = fromIndex,
                                        dragOffsetY = draggedOffsetY,
                                        orderedProjects = orderedProjects,
                                        itemHeights = itemHeights,
                                    )
                                }
                            },
                            onDragEnd = {
                                val updatedOrder = draggedFromIndex?.let { fromIndex ->
                                    val targetIndex = draggedTargetIndex ?: fromIndex
                                    orderedProjects.move(fromIndex, targetIndex)
                                } ?: orderedProjects
                                draggedPath = null
                                draggedFromIndex = null
                                draggedTargetIndex = null
                                draggedOffsetY = 0f
                                if (updatedOrder != recentProjects) {
                                    orderedProjects = updatedOrder
                                    onRecentProjectsReordered(updatedOrder)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingRemovalPath?.let { path ->
        ConfirmRecentProjectRemovalDialog(
            path = path,
            onConfirm = {
                onRecentProjectRemoved(path)
                pendingRemovalPath = null
            },
            onDismiss = { pendingRemovalPath = null },
        )
    }
}

@Composable
private fun SettingsLauncherButton(onClick: () -> Unit) {
    ProjectActionButton(
        onClick = onClick,
        contentDescription = "Open settings",
        icon = {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
            )
        },
    )
}

@Composable
private fun ProjectActionButton(
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        icon()
    }
}

@Composable
internal fun RecentProjectChip(
    path: String,
    isActive: Boolean,
    isDragging: Boolean,
    isDropTarget: Boolean,
    dragOffsetY: Float,
    onHeightChanged: (Float) -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val projectFile = File(path)
    val projectName = projectFile.name.ifBlank { path }
    val parentName = projectFile.parentFile?.name?.takeIf { it.isNotBlank() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffsetY
                shadowElevation = if (isDragging) 18f else 0f
            }
            .onSizeChanged { onHeightChanged(it.height.toFloat()) }
            .clickable(enabled = !isActive, onClick = onOpen)
            .pointerInput(path) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragCancel = onDragEnd,
                    onDragEnd = onDragEnd,
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.y)
                }
            }
            .border(
                width = if (isActive || isDragging) 1.5.dp else 1.dp,
                color = when {
                    isDragging -> FlowMetricSlate
                    isDropTarget -> FlowMetricTeal
                    isActive -> FlowMetricOrange
                    else -> MaterialTheme.colorScheme.outline
                },
                shape = RoundedCornerShape(20.dp),
            ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .background(
                    when {
                        isDragging -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                        isDropTarget -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
                        isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Drag", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    projectName,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(parentName, path).joinToString("  •  "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .background(FlowMetricOrange, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("Active", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
                        .clickable(onClick = onRemove)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
internal fun SettingsDialog(
    appSettings: DesktopAppSettings,
    config: FlowMetricProjectConfig,
    projectPath: String,
    errorLogPath: String,
    errorLogs: List<DesktopErrorLogEntry>,
    onRefreshErrors: () -> Unit,
    onDeleteError: (Int) -> Unit,
    onClearErrors: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (DesktopAppSettings, Set<String>, List<String>) -> Unit,
) {
    var themePreference by remember(appSettings) { mutableStateOf(appSettings.theme) }
    var reopenLastProjectOnLaunch by remember(appSettings) { mutableStateOf(appSettings.reopenLastProjectOnLaunch) }
    var refreshAutomaticallyOnWatchedChanges by remember(appSettings) {
        mutableStateOf(appSettings.refreshAutomaticallyOnWatchedChanges)
    }
    var defaultTab by remember(appSettings) { mutableStateOf(appSettings.defaultTab) }
    var defaultLookbackDays by remember(appSettings) { mutableStateOf(appSettings.defaultLookbackDays) }
    var ignoredExtensionsInput by remember(config) {
        mutableStateOf(config.ignoredExtensions.sorted().joinToString(", "))
    }
    var ignoredPathFragmentsInput by remember(config) {
        mutableStateOf(config.ignoredPathFragments.joinToString("\n"))
    }
    var errorsExpanded by remember { mutableStateOf(false) }
    var errorsPageIndex by remember(errorLogs) { mutableStateOf(0) }

    LaunchedEffect(appSettings) {
        themePreference = appSettings.theme
        reopenLastProjectOnLaunch = appSettings.reopenLastProjectOnLaunch
        refreshAutomaticallyOnWatchedChanges = appSettings.refreshAutomaticallyOnWatchedChanges
        defaultTab = appSettings.defaultTab
        defaultLookbackDays = appSettings.defaultLookbackDays
    }

    LaunchedEffect(config) {
        ignoredExtensionsInput = config.ignoredExtensions.sorted().joinToString(", ")
        ignoredPathFragmentsInput = config.ignoredPathFragments.joinToString("\n")
    }

    val dialogState = rememberDialogState(width = 960.dp, height = 780.dp)

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "FlowMetric Settings",
        state = dialogState,
        resizable = true,
    ) {
        FlowMetricTheme(themePreference = appSettings.theme) {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Change the app theme, tune default behavior, and update project filters from one place.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsSectionCard(
                        title = "App appearance",
                        supportingText = "These preferences are stored in `~/.flowmetric-desktop/app-settings.json`.",
                    ) {
                        SettingsChipRow(
                            label = "Theme",
                            options = DesktopThemePreference.entries.toList(),
                            selected = themePreference,
                            optionLabel = { option ->
                                when (option) {
                                    DesktopThemePreference.SYSTEM -> "System"
                                    DesktopThemePreference.LIGHT -> "Light"
                                    DesktopThemePreference.DARK -> "Dark"
                                }
                            },
                            onSelected = { themePreference = it },
                        )
                        SettingsChipRow(
                            label = "Default tab",
                            options = StartupTabPreference.entries.toList(),
                            selected = defaultTab,
                            optionLabel = { option ->
                                when (option) {
                                    StartupTabPreference.GIT -> "Git"
                                    StartupTabPreference.EVENTS -> "Events"
                                }
                            },
                            onSelected = { defaultTab = it },
                        )
                        SettingsChipRow(
                            label = "Default lookback",
                            options = listOf(3, 7, 14, 30),
                            selected = defaultLookbackDays,
                            optionLabel = { "$it days" },
                            onSelected = { defaultLookbackDays = it },
                        )
                        SettingsToggleRow(
                            title = "Reopen the last recent project on launch",
                            description = "Start in your latest workspace instead of a blank project picker.",
                            checked = reopenLastProjectOnLaunch,
                            onCheckedChange = { reopenLastProjectOnLaunch = it },
                        )
                        SettingsToggleRow(
                            title = "Refresh automatically after watched file changes",
                            description = "FlowMetric will still record detected changes, but this controls whether the dashboard refreshes immediately.",
                            checked = refreshAutomaticallyOnWatchedChanges,
                            onCheckedChange = { refreshAutomaticallyOnWatchedChanges = it },
                        )
                    }
                    SettingsSectionCard(
                        title = "Project filters",
                        supportingText = if (projectPath.isBlank()) {
                            "Choose a project to save ignored extensions and path fragments."
                        } else {
                            "Project-specific filters are stored in `$projectPath/.flowmetric/config.json`."
                        },
                    ) {
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
                    }
                    SettingsSectionCard(
                        title = "Logs & errors",
                        supportingText = "Review the latest desktop app errors when refreshes or tracking flows fail.",
                    ) {
                        val pageCount = if (errorLogs.isEmpty()) 1 else ((errorLogs.size - 1) / ERROR_LOGS_PER_PAGE) + 1
                        val safePageIndex = errorsPageIndex.coerceIn(0, pageCount - 1)
                        val visibleErrors = errorLogs
                            .drop(safePageIndex * ERROR_LOGS_PER_PAGE)
                            .take(ERROR_LOGS_PER_PAGE)

                        Card(
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { errorsExpanded = !errorsExpanded },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(FlowMetricOrange.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                                                .padding(10.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.ReportProblem,
                                                contentDescription = null,
                                                tint = FlowMetricOrange,
                                            )
                                        }
                                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text("Desktop errors", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                if (errorLogs.isEmpty()) "No [ERROR] entries in the desktop log."
                                                else "${errorLogs.size} error entries available",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 13.sp,
                                            )
                                        }
                                    }
                                    Icon(
                                        imageVector = if (errorsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(errorLogPath, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }

                        if (errorsExpanded && errorLogs.isEmpty()) {
                            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text("No desktop errors logged yet.", fontWeight = FontWeight.Medium)
                                    Text(
                                        "When FlowMetric records an app-level failure, it will appear here.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        } else if (errorsExpanded) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Page ${safePageIndex + 1} of $pageCount",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedIconButton(
                                        onClick = onRefreshErrors,
                                        modifier = Modifier.semantics { contentDescription = "Refresh error logs" },
                                    ) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                                    }
                                    OutlinedIconButton(
                                        onClick = { errorsPageIndex = (safePageIndex - 1).coerceAtLeast(0) },
                                        enabled = safePageIndex > 0,
                                        modifier = Modifier.semantics { contentDescription = "Previous error page" },
                                    ) {
                                        Icon(Icons.Outlined.ChevronLeft, contentDescription = null)
                                    }
                                    OutlinedIconButton(
                                        onClick = { errorsPageIndex = (safePageIndex + 1).coerceAtMost(pageCount - 1) },
                                        enabled = safePageIndex < pageCount - 1,
                                        modifier = Modifier.semantics { contentDescription = "Next error page" },
                                    ) {
                                        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                                    }
                                    OutlinedIconButton(
                                        onClick = {
                                            onClearErrors()
                                            errorsPageIndex = 0
                                        },
                                        enabled = errorLogs.isNotEmpty(),
                                        modifier = Modifier.semantics { contentDescription = "Clear all error logs" },
                                    ) {
                                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                                    }
                                }
                            }
                            visibleErrors.forEach { errorEntry ->
                                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text("Error", fontWeight = FontWeight.SemiBold, color = FlowMetricOrange)
                                            Text(
                                                errorEntry.line,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 13.sp,
                                            )
                                        }
                                        OutlinedIconButton(
                                            onClick = { onDeleteError(errorEntry.id) },
                                            modifier = Modifier.semantics { contentDescription = "Delete error entry" },
                                        ) {
                                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = {
                            onSave(
                                DesktopAppSettings(
                                    theme = themePreference,
                                    reopenLastProjectOnLaunch = reopenLastProjectOnLaunch,
                                    refreshAutomaticallyOnWatchedChanges = refreshAutomaticallyOnWatchedChanges,
                                    defaultTab = defaultTab,
                                    defaultLookbackDays = defaultLookbackDays,
                                ),
                                ignoredExtensionsInput.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet(),
                                ignoredPathFragmentsInput.lines().map { it.trim() }.filter { it.isNotBlank() },
                            )
                        }) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    supportingText: String,
    content: @Composable () -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(supportingText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            content()
        }
    }
}

@Composable
private fun <T> SettingsChipRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    label = { Text(optionLabel(option)) },
                )
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

private const val ERROR_LOGS_PER_PAGE = 5

@Composable
private fun ConfirmRecentProjectRemovalDialog(
    path: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val projectName = File(path).name.ifBlank { path }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove Recent Project") },
        text = {
            Text("Do you really want to remove `$projectName` from recent projects? This will only remove it from the recent list.")
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Remove")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun List<String>.move(fromIndex: Int, toIndex: Int): List<String> {
    if (fromIndex == toIndex) return this

    val mutable = toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable.toList()
}

private fun calculateDropTargetIndex(
    sourceIndex: Int,
    dragOffsetY: Float,
    orderedProjects: List<String>,
    itemHeights: Map<String, Float>,
): Int {
    var targetIndex = sourceIndex
    var remainingOffset = dragOffsetY

    if (dragOffsetY > 0f) {
        while (targetIndex < orderedProjects.lastIndex) {
            val nextHeight = itemHeights[orderedProjects[targetIndex + 1]] ?: DEFAULT_RECENT_PROJECT_HEIGHT
            if (remainingOffset < nextHeight / 2f) break
            remainingOffset -= nextHeight
            targetIndex += 1
        }
    } else if (dragOffsetY < 0f) {
        while (targetIndex > 0) {
            val previousHeight = itemHeights[orderedProjects[targetIndex - 1]] ?: DEFAULT_RECENT_PROJECT_HEIGHT
            if (-remainingOffset < previousHeight / 2f) break
            remainingOffset += previousHeight
            targetIndex -= 1
        }
    }

    return targetIndex
}

private const val DEFAULT_RECENT_PROJECT_HEIGHT = 120f
