package com.flowmetric.desktop.git

data class GitDiffDocument(
    val filePath: String,
    val headerLines: List<String>,
    val hunks: List<GitDiffHunk>,
)

data class GitDiffHunk(
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<GitDiffLine>,
)

data class GitDiffLine(
    val rawLine: String,
    val kind: GitDiffLineKind,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val patchOldStart: Int?,
    val patchNewStart: Int?,
)

enum class GitDiffLineKind {
    CONTEXT,
    ADDED,
    REMOVED,
    META,
}

data class GitRevertResult(
    val success: Boolean,
    val message: String,
)
