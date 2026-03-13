package com.flowmetric.desktop.git

import com.flowmetric.shared.model.ChangeClassification
import com.flowmetric.shared.model.ConfidenceLevel
import com.flowmetric.shared.model.GitFileDelta
import com.flowmetric.shared.model.GitFileStatus
import com.flowmetric.shared.model.GitHeuristicAssessment
import com.flowmetric.shared.model.GitWorkingTreeSummary
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.math.roundToInt

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
        val files = assessFiles(aggregatedFiles)
            .sortedWith(
                compareByDescending<GitFileDelta> { it.lastModifiedEpochMillis ?: 0L }
                    .thenByDescending { it.insertedLines + it.deletedLines }
                    .thenBy { it.filePath },
            )

        return GitWorkingTreeSummary(
            available = true,
            repositoryRoot = repoRoot,
            totalInsertedLines = files.sumOf { it.insertedLines },
            totalDeletedLines = files.sumOf { it.deletedLines },
            estimatedAiLines = files.sumOf { it.estimatedAiLines },
            estimatedNonAiLines = files.sumOf { it.estimatedNonAiLines },
            changedFilesCount = files.size,
            files = files,
            heuristicAssessment = assess(files),
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

    private fun runGit(workingDirectory: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return if (exitCode == 0) output else ""
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

    private fun assessFiles(files: List<GitFileDelta>): List<GitFileDelta> {
        val timestamps = files.mapNotNull { it.lastModifiedEpochMillis }.sorted()
        val globalWindowMillis = when {
            timestamps.size >= 2 -> (timestamps.last() - timestamps.first()).coerceAtLeast(0L)
            timestamps.size == 1 -> 0L
            else -> null
        }

        return files.map { file ->
            val touchedLines = file.insertedLines + file.deletedLines
            if (touchedLines == 0) return@map file

            val timingFirst = globalWindowMillis != null && globalWindowMillis <= 300_000L
            val spreadOut = globalWindowMillis != null && globalWindowMillis >= 1_200_000L

            val estimatedAiLines: Int
            val estimatedNonAiLines: Int
            val classification: ChangeClassification
            val confidence: ConfidenceLevel

            when {
                timingFirst && file.insertedLines > 0 -> {
                    estimatedAiLines = file.insertedLines
                    estimatedNonAiLines = file.deletedLines
                    classification = ChangeClassification.ESTIMATED_AI_GENERATED
                    confidence = if (globalWindowMillis <= 120_000L) ConfidenceLevel.MEDIUM else ConfidenceLevel.LOW
                }

                spreadOut -> {
                    estimatedAiLines = 0
                    estimatedNonAiLines = touchedLines
                    classification = ChangeClassification.ESTIMATED_NON_AI
                    confidence = ConfidenceLevel.MEDIUM
                }

                else -> {
                    val likelyAi = when {
                        file.deletedLines >= file.insertedLines * 2 && file.deletedLines >= 40 -> 0.20
                        file.insertedLines <= 18 && file.deletedLines <= 12 -> 0.25
                        else -> 0.50
                    }
                    estimatedAiLines = (touchedLines * likelyAi).roundToInt()
                    estimatedNonAiLines = (touchedLines - estimatedAiLines).coerceAtLeast(0)
                    classification = when {
                        likelyAi <= 0.40 -> ChangeClassification.ESTIMATED_NON_AI
                        else -> ChangeClassification.MIXED_OR_UNCLEAR
                    }
                    confidence = ConfidenceLevel.LOW
                }
            }

            file.copy(
                estimatedAiLines = estimatedAiLines,
                estimatedNonAiLines = estimatedNonAiLines,
                classification = classification,
                confidence = confidence,
            )
        }
    }

    private fun assess(files: List<GitFileDelta>): GitHeuristicAssessment? {
        if (files.isEmpty()) return null

        val inserted = files.sumOf { it.insertedLines }
        val deleted = files.sumOf { it.deletedLines }
        val estimatedAiLines = files.sumOf { it.estimatedAiLines }
        val estimatedNonAiLines = files.sumOf { it.estimatedNonAiLines }
        val changedFiles = files.size
        val untrackedOrAdded = files.count { it.status == GitFileStatus.UNTRACKED || it.status == GitFileStatus.ADDED }
        val largeFiles = files.count { it.insertedLines >= 40 }
        val avgTouchedLines = files.map { it.insertedLines + it.deletedLines }.average()
        val timestamps = files.mapNotNull { it.lastModifiedEpochMillis }.sorted()
        val latestChange = timestamps.maxOrNull()
        val changeWindowMillis = when {
            timestamps.size >= 2 -> (timestamps.last() - timestamps.first()).coerceAtLeast(0L)
            timestamps.size == 1 -> 0L
            else -> null
        }

        return when {
            inserted >= 180 &&
                changedFiles <= 6 &&
                (untrackedOrAdded >= 2 || largeFiles >= 2) &&
                changeWindowMillis != null &&
                changeWindowMillis <= 120_000L ->
                GitHeuristicAssessment(
                    classification = ChangeClassification.ESTIMATED_AI_GENERATED,
                    confidence = ConfidenceLevel.MEDIUM,
                    rationale = "Git diff shows about $estimatedAiLines likely AI-assisted lines versus $estimatedNonAiLines likely non-AI lines, concentrated in a very short timestamp window. In the Git tab, timing now dominates the estimate.",
                    latestChangeEpochMillis = latestChange,
                    changeWindowMillis = changeWindowMillis,
                )

            inserted > 0 &&
                changeWindowMillis != null &&
                changeWindowMillis <= 300_000L ->
                GitHeuristicAssessment(
                    classification = ChangeClassification.ESTIMATED_AI_GENERATED,
                    confidence = ConfidenceLevel.LOW,
                    rationale = "Git diff suggests roughly $estimatedAiLines likely AI-assisted lines and $estimatedNonAiLines likely non-AI lines. The hint comes primarily from tightly clustered file timestamps in the same window.",
                    latestChangeEpochMillis = latestChange,
                    changeWindowMillis = changeWindowMillis,
                )

            deleted >= 120 && deleted > inserted * 2 ->
                GitHeuristicAssessment(
                    classification = ChangeClassification.ESTIMATED_NON_AI,
                    confidence = ConfidenceLevel.LOW,
                    rationale = "Git diff suggests roughly $estimatedNonAiLines likely non-AI lines and only $estimatedAiLines likely AI-assisted lines. The deletion-heavy pattern aligns more with manual refactoring or cleanup than bulk generation.",
                    latestChangeEpochMillis = latestChange,
                    changeWindowMillis = changeWindowMillis,
                )

            changedFiles >= 10 &&
                avgTouchedLines <= 18.0 &&
                changeWindowMillis != null &&
                changeWindowMillis >= 1_200_000L ->
                GitHeuristicAssessment(
                    classification = ChangeClassification.ESTIMATED_NON_AI,
                    confidence = ConfidenceLevel.MEDIUM,
                    rationale = "Git diff suggests roughly $estimatedNonAiLines likely non-AI lines and $estimatedAiLines likely AI-assisted lines. Many small edits spread across a longer timestamp window more often match gradual manual work.",
                    latestChangeEpochMillis = latestChange,
                    changeWindowMillis = changeWindowMillis,
                )

            else ->
                GitHeuristicAssessment(
                    classification = ChangeClassification.MIXED_OR_UNCLEAR,
                    confidence = ConfidenceLevel.LOW,
                    rationale = "Git diff suggests around $estimatedAiLines likely AI-assisted lines and $estimatedNonAiLines likely non-AI lines, but the timestamp pattern is not decisive. Use Git as a complement to tracked edit events, not proof.",
                    latestChangeEpochMillis = latestChange,
                    changeWindowMillis = changeWindowMillis,
                )
        }
    }

    private fun readLastModified(path: Path): Long? =
        runCatching { Files.getLastModifiedTime(path) }
            .map(FileTime::toMillis)
            .getOrNull()
}
