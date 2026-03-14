package com.flowmetric.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import com.flowmetric.shared.model.FlowMetricProjectConfig
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProjectHeader(
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
    var pendingRemovalPath by remember { mutableStateOf<String?>(null) }

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
                    CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), color = Color.White, strokeWidth = 2.dp)
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
                            onRemove = { pendingRemovalPath = path },
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
internal fun RecentProjectChip(
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
            .clickable(enabled = !isActive, onClick = onOpen)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) Color(0xFFD96C2F) else Color(0xFFE1D8C8),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
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
                                .background(Color(0xFFD96C2F), androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
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
                    if (isActive) "Current project" else "Open project",
                    fontSize = 12.sp,
                    color = if (isActive) Color(0xFF7A867D) else Color(0xFF49616D),
                    fontWeight = FontWeight.Medium,
                )
            }
            Box(
                modifier = Modifier
                    .background(Color(0xFFF1E8DA), androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
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
internal fun ProjectConfigDialog(
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
        Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
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
