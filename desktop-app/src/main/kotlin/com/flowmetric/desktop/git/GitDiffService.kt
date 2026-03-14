package com.flowmetric.desktop.git

import com.flowmetric.shared.config.ProjectFileRules
import com.flowmetric.shared.model.FlowMetricProjectConfig
import com.flowmetric.shared.model.GitFileDelta
import com.flowmetric.shared.model.GitFileObservation
import com.flowmetric.shared.model.GitFileStatus
import com.flowmetric.shared.model.GitWorkingTreeSummary
import com.flowmetric.shared.persistence.FlowMetricProjectConfigStore
import com.flowmetric.shared.persistence.FlowMetricStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

class GitDiffService {
    fun summarize(projectRoot: Path): GitWorkingTreeSummary {
        if (!projectRoot.exists()) {
            return GitWorkingTreeSummary(
                available = false,
                message = "Project path does not exist.",
            )
        }

        val repoRoot = runGit(projectRoot, "rev-parse", "--show-toplevel").trim()
        if (repoRoot.isBlank()) {
            return GitWorkingTreeSummary(
                available = false,
                message = "This folder is not inside a Git repository.",
            )
        }

        val repoPath = Path.of(repoRoot)
        val config = FlowMetricProjectConfigStore.projectConfigStore(projectRoot).readOrCreate()
        val tracked = parseTrackedDiff(repoPath)
        val untracked = parseUntrackedFiles(repoPath)
        val aggregatedFiles = (tracked + untracked)
            .groupBy { it.filePath }
            .map { (filePath, deltas) ->
                GitFileDelta(
                    filePath = filePath,
                    insertedLines = deltas.sumOf { it.insertedLines },
                    deletedLines = deltas.sumOf { it.deletedLines },
                    status = deltas.maxBy { statusPriority(it.status) }.status,
                    lastModifiedEpochMillis = deltas.mapNotNull { it.lastModifiedEpochMillis }.maxOrNull(),
                )
            }
            .filter { isTrackable(projectRoot, repoPath, it, config) }
        val files = aggregatedFiles
            .sortedWith(
                compareByDescending<GitFileDelta> { it.lastModifiedEpochMillis ?: 0L }
                    .thenByDescending { it.insertedLines + it.deletedLines }
                    .thenBy { it.filePath },
            )
        val observations = buildObservations(projectRoot, repoPath, files)

        return GitWorkingTreeSummary(
            available = true,
            repositoryRoot = repoRoot,
            totalInsertedLines = files.sumOf { it.insertedLines },
            totalDeletedLines = files.sumOf { it.deletedLines },
            estimatedAiLines = files.sumOf { it.estimatedAiLines },
            estimatedNonAiLines = files.sumOf { it.estimatedNonAiLines },
            changedFilesCount = files.size,
            files = files,
            observations = observations,
            heuristicAssessment = null,
            message = if (files.isEmpty()) "Working tree is clean." else null,
        )
    }

    private fun parseTrackedDiff(repoRoot: Path): List<GitFileDelta> {
        val output = runGit(repoRoot, "diff", "--numstat", "--find-renames", "HEAD")
        return output.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val inserted = parts[0].toIntOrNull() ?: 0
                val deleted = parts[1].toIntOrNull() ?: 0
                val filePath = parts.drop(2).last()
                val absolutePath = repoRoot.resolve(filePath)
                val status = when {
                    inserted > 0 && deleted == 0 -> GitFileStatus.ADDED
                    deleted > 0 && inserted == 0 -> GitFileStatus.DELETED
                    else -> GitFileStatus.MODIFIED
                }
                GitFileDelta(
                    filePath = filePath,
                    insertedLines = inserted,
                    deletedLines = deleted,
                    status = status,
                    lastModifiedEpochMillis = readLastModified(absolutePath),
                )
            }
            .toList()
    }

    private fun parseUntrackedFiles(repoRoot: Path): List<GitFileDelta> {
        val output = runGit(repoRoot, "ls-files", "--others", "--exclude-standard")
        return output.lineSequence()
            .filter { it.isNotBlank() }
            .map { relativePath ->
                val absolutePath = repoRoot.resolve(relativePath)
                GitFileDelta(
                    filePath = relativePath,
                    insertedLines = if (absolutePath.isRegularFile()) countLines(absolutePath) else 0,
                    deletedLines = 0,
                    status = GitFileStatus.UNTRACKED,
                    lastModifiedEpochMillis = readLastModified(absolutePath),
                )
            }
            .toList()
    }

    fun diffForFile(projectRoot: Path, filePath: String, status: GitFileStatus): String {
        val repoRoot = runGit(projectRoot, "rev-parse", "--show-toplevel").trim()
        if (repoRoot.isBlank()) return "Git repository not available."

        val repoPath = Path.of(repoRoot)
        return when (status) {
            GitFileStatus.UNTRACKED, GitFileStatus.ADDED -> buildUntrackedDiff(repoPath.resolve(filePath), filePath)
            else -> runGit(repoPath, "diff", "--", filePath).ifBlank { "No current diff available for this file." }
        }
    }

    private fun runGit(workingDirectory: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return if (exitCode == 0) output else ""
    }

    private fun buildUntrackedDiff(path: Path, filePath: String): String {
        if (!path.exists() || !path.isRegularFile()) {
            return "File is not available on disk."
        }

        val content = runCatching { Files.readString(path) }.getOrDefault("")
        val body = if (content.isBlank()) "" else content.lineSequence().joinToString("\n") { "+$it" }
        return buildString {
            appendLine("diff --git a/$filePath b/$filePath")
            appendLine("new file mode 100644")
            appendLine("--- /dev/null")
            appendLine("+++ b/$filePath")
            appendLine("@@ -0,0 +1,${content.lineSequence().count()} @@")
            append(body)
        }.trimEnd()
    }

    private fun countLines(path: Path): Int {
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesRead: Int
            var lineCount = 0
            var hasContent = false
            var lastByteWasNewline = false

            while (input.read(buffer).also { bytesRead = it } != -1) {
                if (bytesRead == 0) continue
                hasContent = true
                repeat(bytesRead) { index ->
                    if (buffer[index] == '\n'.code.toByte()) {
                        lineCount++
                        lastByteWasNewline = true
                    } else {
                        lastByteWasNewline = false
                    }
                }
            }

            return when {
                !hasContent -> 0
                lastByteWasNewline -> lineCount
                else -> lineCount + 1
            }
        }
    }

    private fun statusPriority(status: GitFileStatus): Int = when (status) {
        GitFileStatus.UNTRACKED -> 5
        GitFileStatus.ADDED -> 4
        GitFileStatus.DELETED -> 3
        GitFileStatus.RENAMED -> 2
        GitFileStatus.TYPE_CHANGED -> 1
        GitFileStatus.MODIFIED, GitFileStatus.UNKNOWN -> 0
    }

    private fun readLastModified(path: Path): Long? =
        runCatching { Files.getLastModifiedTime(path) }
            .map(FileTime::toMillis)
            .getOrNull()

    private fun buildObservations(
        projectRoot: Path,
        repoRoot: Path,
        files: List<GitFileDelta>,
    ): List<GitFileObservation> {
        if (files.isEmpty()) return emptyList()

        val events = FlowMetricStore.projectStore(projectRoot).read().events
        val fileByAbsolutePath = files.associateBy { repoRoot.resolve(it.filePath).normalize().toString() }

        // Prefer tracked edit timestamps so the Git tab reflects when the user actually changed a file.
        val eventBacked = events
            .asSequence()
            .mapNotNull { event ->
                val normalizedPath = runCatching { Path.of(event.filePath).normalize().toString() }.getOrNull() ?: return@mapNotNull null
                val gitFile = fileByAbsolutePath[normalizedPath] ?: return@mapNotNull null
                GitFileObservation(
                    id = event.id,
                    filePath = gitFile.filePath,
                    insertedLines = event.delta.inserted,
                    deletedLines = event.delta.deleted,
                    status = gitFile.status,
                    observedAtEpochMillis = event.timestampEpochMillis,
                    fileModifiedEpochMillis = gitFile.lastModifiedEpochMillis,
                    fromTrackedEvents = true,
                    linePatch = event.metadata.linePatch,
                )
            }
            .toList()

        // Keep Git-only files visible too, but let tracked edit events win when both sources hit the same bucket.
        val gitFallback = files.map { file ->
            GitFileObservation(
                id = UUID.randomUUID().toString(),
                filePath = file.filePath,
                insertedLines = file.insertedLines,
                deletedLines = file.deletedLines,
                status = file.status,
                observedAtEpochMillis = file.lastModifiedEpochMillis ?: System.currentTimeMillis(),
                fileModifiedEpochMillis = file.lastModifiedEpochMillis,
                fromTrackedEvents = false,
                linePatch = null,
            )
        }

        return normalizeObservations(eventBacked + gitFallback)
    }

    // Collapse noisy repeats into one entry per file per short time bucket.
    // If both Git fallback and tracked edit events exist for the same bucket, prefer the tracked event.
    private fun normalizeObservations(observations: List<GitFileObservation>): List<GitFileObservation> =
        observations
            .sortedByDescending { it.observedAtEpochMillis }
            .groupBy { it.filePath to bucketStart(it.observedAtEpochMillis) }
            .map { (_, grouped) ->
                grouped.maxWith(
                    compareBy<GitFileObservation> { it.fromTrackedEvents }
                        .thenBy { it.observedAtEpochMillis },
                )
            }
            .sortedByDescending { it.observedAtEpochMillis }

    private fun bucketStart(epochMillis: Long): Long =
        epochMillis - (epochMillis % OBSERVATION_BUCKET_MS)

    private fun isTrackable(
        projectRoot: Path,
        repoRoot: Path,
        delta: GitFileDelta,
        config: FlowMetricProjectConfig,
    ): Boolean = ProjectFileRules.isTrackable(projectRoot, repoRoot.resolve(delta.filePath), config)

    companion object {
        private const val OBSERVATION_BUCKET_MS = 2 * 60 * 1000L
    }
}
