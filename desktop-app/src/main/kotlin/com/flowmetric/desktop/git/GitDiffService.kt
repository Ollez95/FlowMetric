package com.flowmetric.desktop.git

import com.flowmetric.shared.config.ProjectFileRules
import com.flowmetric.shared.model.FlowMetricProjectConfig
import com.flowmetric.shared.model.GitCommitFileChange
import com.flowmetric.shared.model.GitCommitSummary
import com.flowmetric.shared.model.GitFileDelta
import com.flowmetric.shared.model.GitFileObservation
import com.flowmetric.shared.model.GitFileStatus
import com.flowmetric.shared.model.GitWorkingTreeSummary
import com.flowmetric.shared.persistence.FlowMetricProjectConfigStore
import com.flowmetric.shared.persistence.FlowMetricStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
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
        val currentBranch = runGit(repoPath, "rev-parse", "--abbrev-ref", "HEAD").trim().ifBlank { null }
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
        val commits = parseRecentCommits(repoPath)

        return GitWorkingTreeSummary(
            available = true,
            repositoryRoot = repoRoot,
            currentBranch = currentBranch,
            totalInsertedLines = files.sumOf { it.insertedLines },
            totalDeletedLines = files.sumOf { it.deletedLines },
            estimatedAiLines = files.sumOf { it.estimatedAiLines },
            estimatedNonAiLines = files.sumOf { it.estimatedNonAiLines },
            changedFilesCount = files.size,
            files = files,
            observations = observations,
            commits = commits,
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

    fun diffForCommit(projectRoot: Path, commitHash: String): String {
        val repoRoot = runGit(projectRoot, "rev-parse", "--show-toplevel").trim()
        if (repoRoot.isBlank()) return "Git repository not available."

        return runGit(
            Path.of(repoRoot),
            "show",
            "--stat",
            "--patch",
            "--format=fuller",
            "--date=iso",
            commitHash,
        ).ifBlank { "No commit diff available." }
    }

    fun parseSingleFileDiff(diffText: String): GitDiffDocument? {
        val lines = diffText.lines()
        if (lines.none { it.startsWith("@@") }) return null

        val headerLines = mutableListOf<String>()
        val hunks = mutableListOf<GitDiffHunk>()
        var index = 0
        var filePath: String? = null

        while (index < lines.size && !lines[index].startsWith("@@")) {
            val line = lines[index]
            headerLines += line
            if (line.startsWith("+++ b/")) {
                filePath = line.removePrefix("+++ b/")
            }
            index += 1
        }

        while (index < lines.size) {
            val header = lines[index]
            val match = HUNK_HEADER_REGEX.matchEntire(header) ?: break
            val oldStart = match.groupValues[1].toInt()
            val oldCount = match.groupValues[2].ifBlank { "1" }.toInt()
            val newStart = match.groupValues[3].toInt()
            val newCount = match.groupValues[4].ifBlank { "1" }.toInt()
            index += 1

            val hunkLines = mutableListOf<GitDiffLine>()
            var currentOld = oldStart
            var currentNew = newStart

            while (index < lines.size && !lines[index].startsWith("@@")) {
                val rawLine = lines[index]
                when {
                    rawLine.startsWith("\\") -> {
                        hunkLines += GitDiffLine(
                            rawLine = rawLine,
                            kind = GitDiffLineKind.META,
                            oldLineNumber = null,
                            newLineNumber = null,
                            patchOldStart = null,
                            patchNewStart = null,
                        )
                    }

                    rawLine.startsWith("+") -> {
                        hunkLines += GitDiffLine(
                            rawLine = rawLine,
                            kind = GitDiffLineKind.ADDED,
                            oldLineNumber = null,
                            newLineNumber = currentNew,
                            patchOldStart = currentOld,
                            patchNewStart = currentNew,
                        )
                        currentNew += 1
                    }

                    rawLine.startsWith("-") -> {
                        hunkLines += GitDiffLine(
                            rawLine = rawLine,
                            kind = GitDiffLineKind.REMOVED,
                            oldLineNumber = currentOld,
                            newLineNumber = null,
                            patchOldStart = currentOld,
                            patchNewStart = currentNew,
                        )
                        currentOld += 1
                    }

                    else -> {
                        hunkLines += GitDiffLine(
                            rawLine = rawLine,
                            kind = GitDiffLineKind.CONTEXT,
                            oldLineNumber = currentOld,
                            newLineNumber = currentNew,
                            patchOldStart = null,
                            patchNewStart = null,
                        )
                        currentOld += 1
                        currentNew += 1
                    }
                }
                index += 1
            }

            hunks += GitDiffHunk(
                header = header,
                oldStart = oldStart,
                oldCount = oldCount,
                newStart = newStart,
                newCount = newCount,
                lines = hunkLines,
            )
        }

        return GitDiffDocument(
            filePath = filePath ?: "",
            headerLines = headerLines,
            hunks = hunks,
        )
    }

    fun decoratePatchForObservation(observation: GitFileObservation, patchText: String): String {
        if (patchText.startsWith("diff --git") || patchText.startsWith("--- ") || patchText.startsWith("+++ ")) {
            return patchText
        }

        val path = observation.filePath
        val header = when (observation.status) {
            GitFileStatus.UNTRACKED, GitFileStatus.ADDED -> listOf(
                "diff --git a/$path b/$path",
                "new file mode 100644",
                "--- /dev/null",
                "+++ b/$path",
            )
            GitFileStatus.DELETED -> listOf(
                "diff --git a/$path b/$path",
                "deleted file mode 100644",
                "--- a/$path",
                "+++ /dev/null",
            )
            else -> listOf(
                "diff --git a/$path b/$path",
                "--- a/$path",
                "+++ b/$path",
            )
        }

        return buildString {
            header.forEach(::appendLine)
            append(patchText)
        }
    }

    fun revertHunk(projectRoot: Path, document: GitDiffDocument, hunkIndex: Int): GitRevertResult {
        val hunk = document.hunks.getOrNull(hunkIndex)
            ?: return GitRevertResult(success = false, message = "Hunk is no longer available.")
        val patchText = buildString {
            headerLinesFor(document).forEach(::appendLine)
            appendLine(hunk.header)
            hunk.lines.forEach { appendLine(it.rawLine) }
        }.trimEnd()
        val patchResult = applyReversePatch(projectRoot, patchText)
        return if (patchResult.success) {
            patchResult
        } else {
            applyTextualHunkRevert(projectRoot, document, hunkIndex)
        }
    }

    fun revertLine(projectRoot: Path, document: GitDiffDocument, hunkIndex: Int, lineIndex: Int): GitRevertResult {
        val hunk = document.hunks.getOrNull(hunkIndex)
            ?: return GitRevertResult(success = false, message = "Hunk is no longer available.")
        val line = hunk.lines.getOrNull(lineIndex)
            ?: return GitRevertResult(success = false, message = "Line is no longer available.")
        if (line.kind !in setOf(GitDiffLineKind.ADDED, GitDiffLineKind.REMOVED)) {
            return GitRevertResult(success = false, message = "Only changed lines can be reverted.")
        }

        return applyTextualLineRevert(projectRoot, document, hunkIndex, lineIndex)
    }

    fun revertObservation(projectRoot: Path, observation: GitFileObservation): GitRevertResult {
        return when (observation.status) {
            GitFileStatus.UNTRACKED, GitFileStatus.ADDED -> deleteAddedFile(projectRoot, observation)
            GitFileStatus.DELETED -> restoreDeletedFile(projectRoot, observation)
            else -> revertModifiedFile(projectRoot, observation)
        }
    }

    fun revertObservationGroup(projectRoot: Path, observations: List<GitFileObservation>): GitRevertResult {
        if (observations.isEmpty()) {
            return GitRevertResult(success = false, message = "No files are available to revert.")
        }

        observations.forEach { observation ->
            val result = revertObservation(projectRoot, observation)
            if (!result.success) {
                return GitRevertResult(
                    success = false,
                    message = "Stopped while reverting `${observation.filePath}`. ${result.message}",
                )
            }
        }

        return GitRevertResult(
            success = true,
            message = "Reverted ${observations.size} file change(s) from the selected time block.",
        )
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

    private fun applyTextualHunkRevert(
        projectRoot: Path,
        document: GitDiffDocument,
        hunkIndex: Int,
    ): GitRevertResult {
        val hunk = document.hunks.getOrNull(hunkIndex)
            ?: return GitRevertResult(success = false, message = "Hunk is no longer available.")
        val filePath = resolveDocumentPath(projectRoot, document)
            ?: return GitRevertResult(success = false, message = "File is not available on disk.")
        if (!filePath.exists() || !filePath.isRegularFile()) {
            return GitRevertResult(success = false, message = "File is not available on disk.")
        }

        return runCatching {
            val lines = Files.readAllLines(filePath).toMutableList()
            applyHunkToLines(lines, hunk)
            Files.write(filePath, lines)
            GitRevertResult(success = true, message = "Reverted the selected block.")
        }.getOrElse { error ->
            GitRevertResult(success = false, message = error.message ?: "Could not revert the selected block.")
        }
    }

    private fun revertModifiedFile(projectRoot: Path, observation: GitFileObservation): GitRevertResult {
        val patchText = diffForFile(projectRoot, observation.filePath, observation.status)
        if (patchText.isBlank()) {
            return GitRevertResult(success = false, message = "No current patch is available for this file.")
        }

        val document = parseSingleFileDiff(patchText)
            ?: return GitRevertResult(success = false, message = "Could not parse the current file diff.")
        val filePath = resolveDocumentPath(projectRoot, document)
            ?: return GitRevertResult(success = false, message = "File is not available on disk.")
        if (!filePath.exists() || !filePath.isRegularFile()) {
            return GitRevertResult(success = false, message = "File is not available on disk.")
        }

        return runCatching {
            val lines = Files.readAllLines(filePath).toMutableList()
            document.hunks.asReversed().forEach { hunk ->
                applyHunkToLines(lines, hunk)
            }
            Files.write(filePath, lines)
            GitRevertResult(success = true, message = "Reverted the selected file.")
        }.getOrElse { error ->
            GitRevertResult(success = false, message = error.message ?: "Could not revert the selected file.")
        }
    }

    private fun deleteAddedFile(projectRoot: Path, observation: GitFileObservation): GitRevertResult {
        val path = resolveObservationPath(projectRoot, observation)
            ?: return GitRevertResult(success = false, message = "File is not available on disk.")
        return runCatching {
            Files.deleteIfExists(path)
            GitRevertResult(success = true, message = "Removed the added file.")
        }.getOrElse { error ->
            GitRevertResult(success = false, message = error.message ?: "Could not remove the added file.")
        }
    }

    private fun restoreDeletedFile(projectRoot: Path, observation: GitFileObservation): GitRevertResult {
        val repoRoot = runGit(projectRoot, "rev-parse", "--show-toplevel").trim()
        if (repoRoot.isBlank()) {
            return GitRevertResult(success = false, message = "Git repository not available.")
        }

        val previousContent = runGit(Path.of(repoRoot), "show", "HEAD:${observation.filePath}")
        if (previousContent.isBlank()) {
            return GitRevertResult(success = false, message = "Could not load the deleted file from HEAD.")
        }

        val path = resolveObservationPath(projectRoot, observation)
            ?: return GitRevertResult(success = false, message = "File path could not be resolved.")
        return runCatching {
            path.parent?.let(Files::createDirectories)
            Files.writeString(path, previousContent)
            GitRevertResult(success = true, message = "Restored the deleted file.")
        }.getOrElse { error ->
            GitRevertResult(success = false, message = error.message ?: "Could not restore the deleted file.")
        }
    }

    private fun applyTextualLineRevert(
        projectRoot: Path,
        document: GitDiffDocument,
        hunkIndex: Int,
        lineIndex: Int,
    ): GitRevertResult {
        val hunk = document.hunks.getOrNull(hunkIndex)
            ?: return GitRevertResult(success = false, message = "Hunk is no longer available.")
        val filePath = resolveDocumentPath(projectRoot, document)
            ?: return GitRevertResult(success = false, message = "File is not available on disk.")
        if (!filePath.exists() || !filePath.isRegularFile()) {
            return GitRevertResult(success = false, message = "File is not available on disk.")
        }

        return runCatching {
            val lines = Files.readAllLines(filePath).toMutableList()
            applySingleLineRevert(lines, hunk, lineIndex)
            Files.write(filePath, lines)
            GitRevertResult(success = true, message = "Reverted the selected line.")
        }.getOrElse { error ->
            GitRevertResult(success = false, message = error.message ?: "Could not revert the selected line.")
        }
    }

    private fun resolveDocumentPath(projectRoot: Path, document: GitDiffDocument): Path? {
        if (document.filePath.isBlank()) return null
        val repoRoot = runGit(projectRoot, "rev-parse", "--show-toplevel").trim()
        if (repoRoot.isBlank()) return null
        return Path.of(repoRoot).resolve(document.filePath)
    }

    private fun resolveObservationPath(projectRoot: Path, observation: GitFileObservation): Path? {
        val repoRoot = runGit(projectRoot, "rev-parse", "--show-toplevel").trim()
        if (repoRoot.isBlank()) return null
        return Path.of(repoRoot).resolve(observation.filePath)
    }

    // Fallback path for historical or minimal patches: replay the inverse of the hunk directly on the current file.
    private fun applyHunkToLines(
        lines: MutableList<String>,
        hunk: GitDiffHunk,
    ) {
        var cursor = (hunk.newStart - 1).coerceAtLeast(0)
        hunk.lines.forEach { line ->
            when (line.kind) {
                GitDiffLineKind.CONTEXT -> cursor += 1
                GitDiffLineKind.ADDED -> removeMatchingLine(lines, cursor, line.rawLine.drop(1))
                GitDiffLineKind.REMOVED -> {
                    lines.add(cursor.coerceIn(0, lines.size), line.rawLine.drop(1))
                    cursor += 1
                }
                GitDiffLineKind.META -> Unit
            }
        }
    }

    private fun applySingleLineRevert(
        lines: MutableList<String>,
        hunk: GitDiffHunk,
        lineIndex: Int,
    ) {
        var cursor = (hunk.newStart - 1).coerceAtLeast(0)
        hunk.lines.forEachIndexed { index, line ->
            when (line.kind) {
                GitDiffLineKind.CONTEXT -> cursor += 1
                GitDiffLineKind.ADDED -> {
                    if (index == lineIndex) {
                        removeMatchingLine(lines, cursor, line.rawLine.drop(1))
                        return
                    }
                    cursor += 1
                }
                GitDiffLineKind.REMOVED -> {
                    if (index == lineIndex) {
                        lines.add(cursor.coerceIn(0, lines.size), line.rawLine.drop(1))
                        return
                    }
                }
                GitDiffLineKind.META -> Unit
            }
        }
        error("The selected line can no longer be matched in the current file.")
    }

    private fun removeMatchingLine(
        lines: MutableList<String>,
        cursor: Int,
        expectedContent: String,
    ) {
        val safeCursor = cursor.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val directMatch = lines.getOrNull(safeCursor)
        if (directMatch == expectedContent) {
            lines.removeAt(safeCursor)
            return
        }

        val nearbyIndex = ((safeCursor - 3).coerceAtLeast(0)..(safeCursor + 3).coerceAtMost(lines.lastIndex))
            .firstOrNull { lines[it] == expectedContent }
        if (nearbyIndex != null) {
            lines.removeAt(nearbyIndex)
            return
        }

        error("The selected line can no longer be matched in the current file.")
    }

    private fun currentPatchForObservation(projectRoot: Path, observation: GitFileObservation): String {
        val trackedPatch = observation.linePatch
        return when {
            !trackedPatch.isNullOrBlank() && observation.status !in setOf(GitFileStatus.UNTRACKED, GitFileStatus.ADDED) ->
                decoratePatchForObservation(observation, trackedPatch)
            else -> diffForFile(projectRoot, observation.filePath, observation.status)
        }
    }

    private fun headerLinesFor(document: GitDiffDocument): List<String> =
        if (document.headerLines.isNotEmpty()) {
            document.headerLines
        } else {
            listOf(
                "diff --git a/${document.filePath} b/${document.filePath}",
                "--- a/${document.filePath}",
                "+++ b/${document.filePath}",
            )
        }

    private fun applyReversePatch(projectRoot: Path, patchText: String): GitRevertResult {
        val repoRoot = runGit(projectRoot, "rev-parse", "--show-toplevel").trim()
        if (repoRoot.isBlank()) {
            return GitRevertResult(success = false, message = "Git repository not available.")
        }

        val process = ProcessBuilder(
            listOf("git", "apply", "-R", "--recount", "--unidiff-zero", "--whitespace=nowarn", "-"),
        )
            .directory(Path.of(repoRoot).toFile())
            .redirectErrorStream(true)
            .start()

        process.outputStream.bufferedWriter().use { writer ->
            writer.write(patchText)
        }
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()

        return if (exitCode == 0) {
            GitRevertResult(success = true, message = "Reverted the selected Git change.")
        } else {
            GitRevertResult(
                success = false,
                message = output.ifBlank { "Git could not revert the selected patch." },
            )
        }
    }

    private fun parseRecentCommits(repoRoot: Path): List<GitCommitSummary> {
        val output = runGit(
            repoRoot,
            "log",
            "-n",
            RECENT_COMMITS_LIMIT.toString(),
            "--date=iso-strict",
            "--pretty=format:__COMMIT__%n%H%x1f%h%x1f%ad%x1f%an%x1f%s",
            "--numstat",
        )
        if (output.isBlank()) return emptyList()

        val commits = mutableListOf<GitCommitSummary>()
        var currentMeta: List<String>? = null
        var insertedLines = 0
        var deletedLines = 0
        var changedFiles = 0
        val commitFiles = mutableListOf<GitCommitFileChange>()

        fun flushCurrent() {
            val meta = currentMeta ?: return
            val committedAt = runCatching { Instant.parse(meta[2]).toEpochMilli() }.getOrDefault(0L)
            commits += GitCommitSummary(
                hash = meta[0],
                shortHash = meta[1],
                committedAtEpochMillis = committedAt,
                authorName = meta[3],
                subject = meta[4],
                changedFilesCount = changedFiles,
                insertedLines = insertedLines,
                deletedLines = deletedLines,
                files = commitFiles.toList(),
            )
        }

        output.lineSequence().forEach { line ->
            when {
                line == "__COMMIT__" -> {
                    flushCurrent()
                    currentMeta = null
                    insertedLines = 0
                    deletedLines = 0
                    changedFiles = 0
                    commitFiles.clear()
                }

                currentMeta == null && line.isNotBlank() -> {
                    val meta = line.split('\u001f')
                    if (meta.size == 5) {
                        currentMeta = meta
                    }
                }

                line.isBlank() -> Unit

                else -> {
                    val parts = line.split('\t')
                    if (parts.size >= 3) {
                        val fileInsertedLines = parts[0].toIntOrNull() ?: 0
                        val fileDeletedLines = parts[1].toIntOrNull() ?: 0
                        val filePath = parts.drop(2).last()
                        insertedLines += fileInsertedLines
                        deletedLines += fileDeletedLines
                        changedFiles += 1
                        commitFiles += GitCommitFileChange(
                            filePath = filePath,
                            status = inferCommittedFileStatus(fileInsertedLines, fileDeletedLines),
                            insertedLines = fileInsertedLines,
                            deletedLines = fileDeletedLines,
                        )
                    }
                }
            }
        }

        flushCurrent()
        return commits
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

    private fun inferCommittedFileStatus(insertedLines: Int, deletedLines: Int): GitFileStatus = when {
        insertedLines > 0 && deletedLines == 0 -> GitFileStatus.ADDED
        deletedLines > 0 && insertedLines == 0 -> GitFileStatus.DELETED
        else -> GitFileStatus.MODIFIED
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
        private const val RECENT_COMMITS_LIMIT = 15
        private val HUNK_HEADER_REGEX = Regex("""@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*""")
    }
}
