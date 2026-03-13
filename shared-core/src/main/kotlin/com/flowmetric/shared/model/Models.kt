package com.flowmetric.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class TrackedProject(
    val id: String,
    val rootPath: String,
    val selectedAtEpochMillis: Long,
)

@Serializable
data class FileLineDelta(
    val inserted: Int = 0,
    val deleted: Int = 0,
    val largestInsertedBlock: Int = 0,
    val largestDeletedBlock: Int = 0,
) {
    val changedLines: Int get() = inserted + deleted
}

@Serializable
data class ChangeEvent(
    val id: String,
    val projectId: String,
    val projectPath: String,
    val filePath: String,
    val timestampEpochMillis: Long,
    val sessionId: String,
    val delta: FileLineDelta,
    val metadata: ChangeMetadata,
    val snapshot: HeuristicSnapshot,
)

@Serializable
data class ChangeMetadata(
    val source: EventSource,
    val fileExtension: String,
    val languageHint: String? = null,
    val latestContentHash: String? = null,
    val millisSincePreviousEvent: Long? = null,
    val sessionEventIndex: Int = 1,
    val sessionDurationMillis: Long = 0,
    val filesTouchedInSession: Int = 1,
)

@Serializable
enum class EventSource {
    DOCUMENT_SAVE,
    EXTERNAL_FILE_CHANGE,
    MANUAL_IMPORT,
}

@Serializable
data class HeuristicSnapshot(
    val classification: ChangeClassification,
    val confidence: ConfidenceLevel,
    val confidenceScore: Double,
    val estimatedAiLines: Int,
    val estimatedNonAiLines: Int,
    val matchedSignals: List<HeuristicSignal>,
    val notes: String,
)

@Serializable
enum class ChangeClassification {
    ESTIMATED_AI_GENERATED,
    ESTIMATED_NON_AI,
    MIXED_OR_UNCLEAR,
}

@Serializable
enum class ConfidenceLevel {
    LOW,
    MEDIUM,
    HIGH,
}

@Serializable
enum class HeuristicSignal {
    LARGE_INSERTION,
    CONTIGUOUS_BLOCK_INSERTION,
    PASTE_LIKE_BURST,
    BULK_INSERT_THEN_CLEANUP,
    REPEATED_STRUCTURED_EDITS,
    GRADUAL_SMALL_EDITS,
    HIGH_DELETE_RATIO,
    SESSION_CONTINUATION,
}

@Serializable
data class SessionSummary(
    val sessionId: String,
    val projectId: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val fileCount: Int,
    val totalInserted: Int,
    val totalDeleted: Int,
    val classification: ChangeClassification,
    val confidence: ConfidenceLevel,
)

@Serializable
data class FileEstimate(
    val filePath: String,
    val changedLines: Int,
    val estimatedAiLines: Int,
    val estimatedNonAiLines: Int,
    val classification: ChangeClassification,
    val confidence: ConfidenceLevel,
    val latestTimestampEpochMillis: Long,
    val sessionCount: Int,
)

@Serializable
data class TrendPoint(
    val dayLabel: String,
    val estimatedAiLines: Int,
    val estimatedNonAiLines: Int,
)

@Serializable
data class DashboardMetrics(
    val totalProjectLines: Int,
    val changedLines: Int,
    val estimatedAiLines: Int,
    val estimatedNonAiLines: Int,
    val aiPercentage: Double,
    val nonAiPercentage: Double,
    val files: List<FileEstimate>,
    val sessions: List<SessionSummary>,
    val trends: List<TrendPoint>,
)

@Serializable
data class GitFileDelta(
    val filePath: String,
    val insertedLines: Int,
    val deletedLines: Int,
    val status: GitFileStatus,
    val lastModifiedEpochMillis: Long? = null,
    val estimatedAiLines: Int = 0,
    val estimatedNonAiLines: Int = 0,
    val classification: ChangeClassification = ChangeClassification.MIXED_OR_UNCLEAR,
    val confidence: ConfidenceLevel = ConfidenceLevel.LOW,
)

@Serializable
enum class GitFileStatus {
    MODIFIED,
    ADDED,
    DELETED,
    RENAMED,
    UNTRACKED,
    TYPE_CHANGED,
    UNKNOWN,
}

@Serializable
data class GitWorkingTreeSummary(
    val available: Boolean,
    val repositoryRoot: String? = null,
    val totalInsertedLines: Int = 0,
    val totalDeletedLines: Int = 0,
    val estimatedAiLines: Int = 0,
    val estimatedNonAiLines: Int = 0,
    val changedFilesCount: Int = 0,
    val files: List<GitFileDelta> = emptyList(),
    val heuristicAssessment: GitHeuristicAssessment? = null,
    val message: String? = null,
)

@Serializable
data class GitHeuristicAssessment(
    val classification: ChangeClassification,
    val confidence: ConfidenceLevel,
    val rationale: String,
    val latestChangeEpochMillis: Long? = null,
    val changeWindowMillis: Long? = null,
)

@Serializable
data class FlowMetricSnapshot(
    val projects: List<TrackedProject> = emptyList(),
    val events: List<ChangeEvent> = emptyList(),
)

@Serializable
data class AnalyticsFilter(
    val projectPath: String,
    val fromEpochMillis: Long? = null,
    val toEpochMillis: Long? = null,
    val confidence: Set<ConfidenceLevel> = ConfidenceLevel.entries.toSet(),
)
