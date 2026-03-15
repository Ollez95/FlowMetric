package com.flowmetric.shared.heuristics

import com.flowmetric.shared.model.ChangeClassification
import com.flowmetric.shared.model.ChangeEvent
import com.flowmetric.shared.model.ConfidenceLevel
import com.flowmetric.shared.model.EventSource
import com.flowmetric.shared.model.HeuristicSignal
import com.flowmetric.shared.model.HeuristicSnapshot
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

data class HeuristicContext(
    val previousEventForFile: ChangeEvent? = null,
    val previousEventsInSession: List<ChangeEvent> = emptyList(),
    val millisSincePreviousEvent: Long? = null,
    val sessionDurationMillis: Long = 0,
    val filesTouchedInSession: Int = 1,
    val currentSource: EventSource = EventSource.DOCUMENT_SAVE,
)

class HeuristicScorer {
    fun score(
        insertedLines: Int,
        deletedLines: Int,
        timestampEpochMillis: Long,
        largestInsertedBlock: Int = insertedLines,
        context: HeuristicContext = HeuristicContext(),
    ): HeuristicSnapshot {
        val changedLines = insertedLines + deletedLines
        if (changedLines == 0) {
            return HeuristicSnapshot(
                classification = ChangeClassification.MIXED_OR_UNCLEAR,
                confidence = ConfidenceLevel.LOW,
                confidenceScore = 0.0,
                estimatedAiLines = 0,
                estimatedNonAiLines = 0,
                matchedSignals = emptyList(),
                notes = "No meaningful line delta was detected.",
            )
        }

        if (context.currentSource == EventSource.CODEX_PATCH) {
            return HeuristicSnapshot(
                classification = ChangeClassification.ESTIMATED_AI_GENERATED,
                confidence = ConfidenceLevel.HIGH,
                confidenceScore = 1.0,
                estimatedAiLines = changedLines,
                estimatedNonAiLines = 0,
                matchedSignals = listOf(HeuristicSignal.CONTIGUOUS_BLOCK_INSERTION),
                notes = "Recorded as an explicit Codex patch event rather than inferred from heuristics.",
            )
        }

        var aiScore = 0.0
        var nonAiScore = 0.0
        val signals = mutableListOf<HeuristicSignal>()

        if (deletedLines >= insertedLines && deletedLines >= 8) {
            nonAiScore += if (deletedLines >= insertedLines * 2 || insertedLines == 0) 0.55 else 0.35
            signals += HeuristicSignal.HIGH_DELETE_RATIO
        }

        if (insertedLines >= 25 && insertedLines >= deletedLines * 2) {
            aiScore += 0.35
            signals += HeuristicSignal.LARGE_INSERTION
        }

        if (largestInsertedBlock >= 12) {
            when (context.currentSource) {
                EventSource.EXTERNAL_FILE_CHANGE -> {
                    aiScore += if (largestInsertedBlock >= 24) 0.34 else 0.22
                    signals += HeuristicSignal.CONTIGUOUS_BLOCK_INSERTION
                }

                EventSource.DOCUMENT_SAVE, EventSource.MANUAL_IMPORT -> {
                    aiScore += if (largestInsertedBlock >= 24) 0.12 else 0.06
                    signals += HeuristicSignal.CONTIGUOUS_BLOCK_INSERTION
                }
            }
        }

        val previousEvent = context.previousEventForFile
        val deltaMillis = context.millisSincePreviousEvent
            ?: previousEvent?.let { timestampEpochMillis - it.timestampEpochMillis }
        if (previousEvent != null && deltaMillis != null) {
            if (deltaMillis in 0..15_000 && (insertedLines >= 20 || largestInsertedBlock >= 12)) {
                if (context.currentSource == EventSource.EXTERNAL_FILE_CHANGE) {
                    aiScore += 0.24
                } else {
                    nonAiScore += 0.18
                }
                signals += HeuristicSignal.PASTE_LIKE_BURST
            }

            if (deltaMillis in 0..180_000 &&
                previousEvent.delta.inserted >= 20 &&
                insertedLines <= 8 &&
                deletedLines <= 8
            ) {
                aiScore += 0.15
                signals += HeuristicSignal.BULK_INSERT_THEN_CLEANUP
            }

            if (deltaMillis in 0..600_000) {
                signals += HeuristicSignal.SESSION_CONTINUATION
            }
        }

        if (context.sessionDurationMillis in 0..180_000 &&
            context.previousEventsInSession.size >= 2 &&
            context.filesTouchedInSession >= 2 &&
            insertedLines >= 12
        ) {
            aiScore += 0.18
            signals += HeuristicSignal.REPEATED_STRUCTURED_EDITS
        }

        if (context.previousEventsInSession.size >= 2) {
            val similarInsertions = context.previousEventsInSession.count {
                (it.delta.inserted - insertedLines).absoluteValue <= 3 && insertedLines >= 8
            }
            if (similarInsertions >= 2) {
                aiScore += 0.20
                signals += HeuristicSignal.REPEATED_STRUCTURED_EDITS
            }
        }

        if (insertedLines in 1..12 && deletedLines in 0..8 && largestInsertedBlock < 12) {
            nonAiScore += 0.30
            signals += HeuristicSignal.GRADUAL_SMALL_EDITS
        }

        if (deletedLines > insertedLines && deletedLines >= 10) {
            nonAiScore += 0.20
            signals += HeuristicSignal.HIGH_DELETE_RATIO
        }

        if (deltaMillis != null && deltaMillis > 120_000 && insertedLines in 1..10 && deletedLines in 0..6) {
            nonAiScore += 0.15
            signals += HeuristicSignal.GRADUAL_SMALL_EDITS
        }

        if (context.currentSource == EventSource.DOCUMENT_SAVE &&
            largestInsertedBlock >= 12 &&
            context.filesTouchedInSession == 1 &&
            deltaMillis != null &&
            deltaMillis <= 15_000
        ) {
            nonAiScore += 0.12
        }

        val totalScore = aiScore + nonAiScore
        val aiShare = if (totalScore == 0.0) 0.5 else (aiScore / totalScore).coerceIn(0.0, 1.0)
        val classification = when {
            aiShare >= 0.60 -> ChangeClassification.ESTIMATED_AI_GENERATED
            aiShare <= 0.40 -> ChangeClassification.ESTIMATED_NON_AI
            else -> ChangeClassification.MIXED_OR_UNCLEAR
        }

        val confidenceScore = when {
            signals.isEmpty() -> 0.25
            signals.size == 1 -> 0.45
            signals.size == 2 -> 0.68
            else -> 0.85
        }
        val confidence = when {
            confidenceScore >= 0.75 -> ConfidenceLevel.HIGH
            confidenceScore >= 0.5 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }

        val estimatedAiLines = (changedLines * aiShare).roundToInt()
        val estimatedNonAiLines = (changedLines - estimatedAiLines).coerceAtLeast(0)
        val notes = buildString {
            append("Estimated from edit patterns, not source attribution.")
            if (signals.isNotEmpty()) {
                append(" Signals: ")
                append(signals.joinToString())
                append(".")
            }
        }

        return HeuristicSnapshot(
            classification = classification,
            confidence = confidence,
            confidenceScore = confidenceScore,
            estimatedAiLines = estimatedAiLines,
            estimatedNonAiLines = estimatedNonAiLines,
            matchedSignals = signals.distinct(),
            notes = notes,
        )
    }
}
