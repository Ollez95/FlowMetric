package com.flowmetric.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.flowmetric.desktop.git.GitDiffDocument
import com.flowmetric.desktop.git.GitDiffLine
import com.flowmetric.desktop.git.GitDiffLineKind
import com.flowmetric.shared.model.GitCommitFileChange
import com.flowmetric.shared.model.GitCommitSummary
import com.flowmetric.shared.model.GitFileObservation
import com.flowmetric.shared.model.GitFileStatus
import com.flowmetric.shared.model.GitWorkingTreeSummary
import java.text.SimpleDateFormat
import java.util.Date

@Composable
internal fun GitSection(
    summary: GitWorkingTreeSummary?,
    selectedCommit: GitCommitSummary?,
    isReverting: Boolean,
    onSelectObservation: (GitFileObservation) -> Unit,
    onSelectCommit: (GitCommitSummary) -> Unit,
    onSelectUncommitted: () -> Unit,
    onRevertObservation: (GitFileObservation) -> Unit,
    onRevertObservationGroup: (List<GitFileObservation>) -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Git working tree", fontWeight = FontWeight.SemiBold)
            when {
                summary == null -> Text("Git diff has not been loaded yet.")
                !summary.available -> Text(summary.message ?: "Git is not available for this folder.")
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SummaryCard("Branch", summary.currentBranch ?: "Detached", Color(0xFF7A3E65))
                        SummaryCard("Git files", summary.changedFilesCount.toString(), Color(0xFF172A3A))
                        SummaryCard("Git +lines", summary.totalInsertedLines.toString(), Color(0xFF2A7F62))
                        SummaryCard("Git -lines", summary.totalDeletedLines.toString(), Color(0xFFD96C2F))
                    }
                    GitScopeSelector(
                        currentBranch = summary.currentBranch,
                        commits = summary.commits,
                        selectedCommit = selectedCommit,
                        uncommittedCount = summary.observations.size,
                        onSelectCommit = onSelectCommit,
                        onSelectUncommitted = onSelectUncommitted,
                    )
                    if (selectedCommit != null) {
                        CommitFilesSection(selectedCommit.files)
                    } else {
                        UncommittedFilesSection(
                            summary = summary,
                            isReverting = isReverting,
                            onSelectObservation = onSelectObservation,
                            onRevertObservation = onRevertObservation,
                            onRevertObservationGroup = onRevertObservationGroup,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GitScopeSelector(
    currentBranch: String?,
    commits: List<GitCommitSummary>,
    selectedCommit: GitCommitSummary?,
    uncommittedCount: Int,
    onSelectCommit: (GitCommitSummary) -> Unit,
    onSelectUncommitted: () -> Unit,
) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7F4EE))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Review source", fontWeight = FontWeight.SemiBold)
            currentBranch?.let {
                Text("Branch: $it", color = Color(0xFF6B7B83), fontSize = 12.sp)
            }
            ScopeCard(
                title = "Uncommitted changes",
                subtitle = "$uncommittedCount tracked timeline entries",
                meta = "Working tree",
                selected = selectedCommit == null,
                onClick = onSelectUncommitted,
            )
            if (commits.isNotEmpty()) {
                Text("Recent commits", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Color(0xFF49616D))
                commits.forEach { commit ->
                    ScopeCard(
                        title = commit.subject,
                        subtitle = "${commit.shortHash} · ${commit.authorName}",
                        meta = "${commit.changedFilesCount} files · ${formatTimestamp(commit.committedAtEpochMillis)}",
                        selected = selectedCommit?.hash == commit.hash,
                        onClick = { onSelectCommit(commit) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeCard(
    title: String,
    subtitle: String,
    meta: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (selected) Color(0xFFD96C2F) else Color(0xFFE2D8C8)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFFFFF2E8) else Color.White, RoundedCornerShape(14.dp))
            .border(1.dp, accent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color(0xFF49616D), fontSize = 12.sp)
        }
        Text(meta, color = Color(0xFF6B7B83), fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UncommittedFilesSection(
    summary: GitWorkingTreeSummary,
    isReverting: Boolean,
    onSelectObservation: (GitFileObservation) -> Unit,
    onRevertObservation: (GitFileObservation) -> Unit,
    onRevertObservationGroup: (List<GitFileObservation>) -> Unit,
) {
    if (summary.observations.isEmpty()) {
        Text(summary.message ?: "Working tree is clean.")
        return
    }

    val timelineColors = listOf(
        Color(0xFFD96C2F),
        Color(0xFF2A7F62),
        Color(0xFF49616D),
        Color(0xFF7A3E65),
        Color(0xFFB7791F),
    )
    val groupedObservations = observationGroups(summary.observations)
    val pageCount = groupedObservations.pageCount(UNCOMMITTED_GROUPS_PER_PAGE)
    var pageIndex by remember(summary.observations) { mutableStateOf(0) }
    var selectedTab by remember(summary.observations) { mutableStateOf(UncommittedTab.FILES) }
    val safePageIndex = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val visibleGroups = groupedObservations.pageSlice(safePageIndex, UNCOMMITTED_GROUPS_PER_PAGE)

    Text("Uncommitted file history", fontWeight = FontWeight.SemiBold)
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            UncommittedTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.title) },
                )
            }
        }
    }
    when (selectedTab) {
        UncommittedTab.FILES -> {
            Text(
                "${summary.files.size} current file${if (summary.files.size == 1) "" else "s"} changed",
                color = Color(0xFF49616D),
                fontSize = 12.sp,
            )
            CurrentChangedFilesSection(
                summary = summary,
                isReverting = isReverting,
                onSelectObservation = onSelectObservation,
                onRevertObservation = onRevertObservation,
            )
        }

        UncommittedTab.HISTORY -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Page ${safePageIndex + 1} of ${pageCount.coerceAtLeast(1)}",
                    color = Color(0xFF49616D),
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
                val newestTimestamp = group.maxOf { it.observedAtEpochMillis }
                val oldestTimestamp = group.minOf { it.observedAtEpochMillis }
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Changed around ${formatTimestamp(group.first().observedAtEpochMillis)}",
                                color = accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            RevertActionButton(
                                enabled = !isReverting,
                                onClick = { onRevertObservationGroup(group) },
                            )
                        }
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White)
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "History: ${formatTimestamp(oldestTimestamp)} to ${formatTimestamp(newestTimestamp)}",
                                        color = Color(0xFF49616D),
                                        fontSize = 12.sp,
                                    )
                                    Text(
                                        "${uniqueFiles.size} file${if (uniqueFiles.size == 1) "" else "s"} changed",
                                        color = Color(0xFF49616D),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Text(
                                    "Files: ${uniqueFiles.joinToString(", ")}",
                                    color = Color(0xFF6B7B83),
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        group.forEach { observation ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF7F4EE), RoundedCornerShape(14.dp))
                                    .clickable { onSelectObservation(observation) }
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
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("+${observation.insertedLines} / -${observation.deletedLines}")
                                    RevertActionButton(
                                        enabled = !isReverting,
                                        onClick = { onRevertObservation(observation) },
                                    )
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
private fun CurrentChangedFilesSection(
    summary: GitWorkingTreeSummary,
    isReverting: Boolean,
    onSelectObservation: (GitFileObservation) -> Unit,
    onRevertObservation: (GitFileObservation) -> Unit,
) {
    val latestObservationByFile = summary.observations
        .sortedByDescending { it.observedAtEpochMillis }
        .associateBy { it.filePath }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        summary.files.forEach { file ->
            val observation = latestObservationByFile[file.filePath]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF7F4EE), RoundedCornerShape(14.dp))
                    .clickable(enabled = observation != null) { observation?.let(onSelectObservation) }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(file.filePath, fontWeight = FontWeight.Medium)
                    Text(
                        gitStatusLabel(file.status),
                        color = gitStatusColor(file.status),
                        fontSize = 12.sp,
                    )
                    observation?.let {
                        Text(
                            "Latest change: ${formatTimestamp(it.observedAtEpochMillis)}",
                            color = Color(0xFF6B7B83),
                            fontSize = 12.sp,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("+${file.insertedLines} / -${file.deletedLines}")
                    observation?.let {
                        RevertActionButton(
                            enabled = !isReverting,
                            onClick = { onRevertObservation(it) },
                        )
                    }
                }
            }
        }
    }
}

private enum class UncommittedTab(val title: String) {
    FILES("Files Changed"),
    HISTORY("History"),
}

@Composable
private fun CommitFilesSection(files: List<GitCommitFileChange>) {
    Text("Files in selected commit", fontWeight = FontWeight.SemiBold)
    if (files.isEmpty()) {
        Text("No file list available for this commit.")
        return
    }

    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7F4EE))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            files.forEach { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.filePath, fontWeight = FontWeight.Medium)
                        Text(
                            gitStatusLabel(file.status),
                            color = gitStatusColor(file.status),
                            fontSize = 12.sp,
                        )
                    }
                    Text("+${file.insertedLines} / -${file.deletedLines}")
                }
            }
        }
    }
}

@Composable
internal fun GitDetailPanel(
    modifier: Modifier,
    selectedCommit: GitCommitSummary?,
    selectedObservation: GitFileObservation?,
    diffPreview: String?,
    diffDocument: GitDiffDocument?,
    isReverting: Boolean,
    onRevertHunk: (Int) -> Unit,
    onRevertLine: (Int, Int) -> Unit,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Git Review", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "This tab is a plain Git working-tree view. It shows changed files, commit history, and diffs without any AI classification.",
                color = Color(0xFF49616D),
            )
            when {
                selectedCommit != null -> {
                    Text(selectedCommit.subject, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Text("Commit ${selectedCommit.shortHash}", color = Color(0xFF49616D))
                    Text("Author: ${selectedCommit.authorName}")
                    Text("Committed at: ${formatTimestamp(selectedCommit.committedAtEpochMillis)}")
                    Text("Changed files: ${selectedCommit.changedFilesCount}")
                    Text("Inserted lines: ${selectedCommit.insertedLines}")
                    Text("Deleted lines: ${selectedCommit.deletedLines}")
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("How to read this", fontWeight = FontWeight.SemiBold)
                            Text("This diff comes directly from the selected Git commit, so it shows the patch that was committed at that moment in history.")
                        }
                    }
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Commit changes", fontWeight = FontWeight.SemiBold)
                            GitDiffPreview(
                                diffPreview = diffPreview,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                        }
                    }
                }

                selectedObservation != null -> {
                    Text(java.io.File(selectedObservation.filePath).name, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
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
                            if (diffDocument != null) {
                                GitDiffActions(
                                    diffDocument = diffDocument,
                                    isReverting = isReverting,
                                    onRevertHunk = onRevertHunk,
                                    onRevertLine = onRevertLine,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                )
                            } else {
                                GitDiffPreview(
                                    diffPreview = diffPreview,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                )
                            }
                        }
                    }
                }

                else -> {
                    Text("Select either `Uncommitted changes` or a commit, then choose a file or inspect the full commit diff.")
                }
            }
        }
    }
}

@Composable
internal fun GitDiffPreview(diffPreview: String?, modifier: Modifier = Modifier) {
    val diffText = diffPreview ?: "Select a Git timeline entry or commit to load its diff."
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
private fun GitDiffActions(
    diffDocument: GitDiffDocument,
    isReverting: Boolean,
    onRevertHunk: (Int) -> Unit,
    onRevertLine: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color(0xFFFBF8F2), RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        diffDocument.hunks.forEachIndexed { hunkIndex, hunk ->
            Card(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(hunk.header, color = Color(0xFFB7791F), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        FilledTonalIconButton(
                            onClick = { onRevertHunk(hunkIndex) },
                            enabled = !isReverting,
                        ) {
                            RevertActionContent(isReverting)
                        }
                    }
                    hunk.lines.forEachIndexed { lineIndex, line ->
                        GitDiffActionLine(
                            line = line,
                            isReverting = isReverting,
                            onRevert = if (line.kind == GitDiffLineKind.ADDED || line.kind == GitDiffLineKind.REMOVED) {
                                { onRevertLine(hunkIndex, lineIndex) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GitDiffActionLine(
    line: GitDiffLine,
    isReverting: Boolean,
    onRevert: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = line.rawLine.ifEmpty { " " },
            color = gitDiffLineColor(line.rawLine),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        if (onRevert != null) {
            FilledTonalIconButton(onClick = onRevert, enabled = !isReverting) {
                RevertActionContent(isReverting = false)
            }
        }
    }
}

@Composable
private fun RevertActionButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled) {
        RevertActionContent(isReverting = false)
    }
}

@Composable
private fun RevertActionContent(isReverting: Boolean) {
    if (isReverting) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    } else {
        Text("↶", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

internal fun gitStatusLabel(status: GitFileStatus): String = when (status) {
    GitFileStatus.MODIFIED -> "Modified"
    GitFileStatus.ADDED -> "Added"
    GitFileStatus.DELETED -> "Deleted"
    GitFileStatus.RENAMED -> "Renamed"
    GitFileStatus.UNTRACKED -> "Untracked"
    GitFileStatus.TYPE_CHANGED -> "Type changed"
    GitFileStatus.UNKNOWN -> "Unknown"
}

internal fun gitStatusColor(status: GitFileStatus): Color = when (status) {
    GitFileStatus.ADDED, GitFileStatus.UNTRACKED -> Color(0xFF2A7F62)
    GitFileStatus.DELETED -> Color(0xFFD96C2F)
    GitFileStatus.RENAMED, GitFileStatus.TYPE_CHANGED -> Color(0xFF7A3E65)
    GitFileStatus.MODIFIED, GitFileStatus.UNKNOWN -> Color(0xFF49616D)
}

internal fun gitDiffLineColor(line: String): Color = when {
    line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff --git") -> Color(0xFF7A3E65)
    line.startsWith("@@") -> Color(0xFFB7791F)
    line.startsWith("+") -> Color(0xFF2A7F62)
    line.startsWith("-") -> Color(0xFFD96C2F)
    else -> Color(0xFF49616D)
}

internal fun observationGroups(observations: List<GitFileObservation>): List<List<GitFileObservation>> {
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

internal fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(epochMillis))

private fun <T> List<T>.pageCount(pageSize: Int): Int =
    if (isEmpty()) 0 else ((size + pageSize - 1) / pageSize)

private fun <T> List<T>.pageSlice(pageIndex: Int, pageSize: Int): List<T> {
    if (isEmpty()) return emptyList()
    val fromIndex = (pageIndex * pageSize).coerceAtMost(size)
    val toIndex = (fromIndex + pageSize).coerceAtMost(size)
    return subList(fromIndex, toIndex)
}

private const val UNCOMMITTED_GROUPS_PER_PAGE = 6
