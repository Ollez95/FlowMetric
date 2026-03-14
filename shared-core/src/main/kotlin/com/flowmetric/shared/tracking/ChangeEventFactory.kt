package com.flowmetric.shared.tracking

import com.flowmetric.shared.analytics.LineDiffEstimator
import com.flowmetric.shared.analytics.LinePatchBuilder
import com.flowmetric.shared.heuristics.HeuristicContext
import com.flowmetric.shared.heuristics.HeuristicScorer
import com.flowmetric.shared.model.ChangeEvent
import com.flowmetric.shared.model.ChangeMetadata
import com.flowmetric.shared.model.EventSource
import com.flowmetric.shared.model.TrackedProject
import java.security.MessageDigest
import java.util.UUID

data class ChangeEventRequest(
    val projectPath: String,
    val filePath: String,
    val fileExtension: String,
    val languageHint: String? = null,
    val branchName: String? = null,
    val headCommitHash: String? = null,
    val previousText: String,
    val currentText: String,
    val source: EventSource,
    val existingEvents: List<ChangeEvent>,
    val timestampEpochMillis: Long,
)

data class PreparedChangeEvent(
    val project: TrackedProject,
    val event: ChangeEvent,
)

class ChangeEventFactory(
    private val scorer: HeuristicScorer = HeuristicScorer(),
) {
    fun build(request: ChangeEventRequest): PreparedChangeEvent? {
        if (request.previousText == request.currentText) return null

        val delta = LineDiffEstimator.estimate(request.previousText, request.currentText)
        if (delta.changedLines == 0) return null

        val previousEvent = request.existingEvents
            .asSequence()
            .filter { it.filePath == request.filePath }
            .maxByOrNull { it.timestampEpochMillis }
        val previousGlobalEvent = request.existingEvents.maxByOrNull { it.timestampEpochMillis }
        val sessionId = resolveSessionId(request.existingEvents, request.timestampEpochMillis)
        val sessionEvents = request.existingEvents.filter { it.sessionId == sessionId }
        val sessionStart = sessionEvents.minOfOrNull { it.timestampEpochMillis } ?: request.timestampEpochMillis
        val millisSincePreviousEvent = previousGlobalEvent?.let { request.timestampEpochMillis - it.timestampEpochMillis }
        val filesTouchedInSession = (sessionEvents.map { it.filePath } + request.filePath).distinct().size

        val snapshot = scorer.score(
            insertedLines = delta.inserted,
            deletedLines = delta.deleted,
            timestampEpochMillis = request.timestampEpochMillis,
            largestInsertedBlock = delta.largestInsertedBlock,
            context = HeuristicContext(
                previousEventForFile = previousEvent,
                previousEventsInSession = sessionEvents,
                millisSincePreviousEvent = millisSincePreviousEvent,
                sessionDurationMillis = request.timestampEpochMillis - sessionStart,
                filesTouchedInSession = filesTouchedInSession,
                currentSource = request.source,
            ),
        )

        val project = TrackedProject(
            id = request.projectPath,
            rootPath = request.projectPath,
            selectedAtEpochMillis = request.timestampEpochMillis,
        )
        val event = ChangeEvent(
            id = UUID.randomUUID().toString(),
            projectId = project.id,
            projectPath = project.rootPath,
            filePath = request.filePath,
            timestampEpochMillis = request.timestampEpochMillis,
            sessionId = sessionId,
            delta = delta,
            metadata = ChangeMetadata(
                source = request.source,
                fileExtension = request.fileExtension.removePrefix("."),
                languageHint = request.languageHint,
                branchName = request.branchName,
                headCommitHash = request.headCommitHash,
                latestContentHash = request.currentText.sha256(),
                linePatch = LinePatchBuilder.build(request.previousText, request.currentText),
                millisSincePreviousEvent = millisSincePreviousEvent,
                sessionEventIndex = sessionEvents.size + 1,
                sessionDurationMillis = request.timestampEpochMillis - sessionStart,
                filesTouchedInSession = filesTouchedInSession,
            ),
            snapshot = snapshot,
        )

        return PreparedChangeEvent(
            project = project,
            event = event,
        )
    }

    private fun resolveSessionId(existingEvents: List<ChangeEvent>, now: Long): String {
        val lastEvent = existingEvents.maxByOrNull { it.timestampEpochMillis }
        return if (lastEvent != null && now - lastEvent.timestampEpochMillis <= SESSION_WINDOW_MS) {
            lastEvent.sessionId
        } else {
            UUID.randomUUID().toString()
        }
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val SESSION_WINDOW_MS = 10 * 60 * 1000L
    }
}
