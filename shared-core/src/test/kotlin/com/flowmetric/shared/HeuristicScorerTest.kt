package com.flowmetric.shared

import com.flowmetric.shared.heuristics.HeuristicScorer
import com.flowmetric.shared.heuristics.HeuristicContext
import com.flowmetric.shared.model.ChangeClassification
import com.flowmetric.shared.model.EventSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeuristicScorerTest {
    private val scorer = HeuristicScorer()

    @Test
    fun `large insertion trends toward estimated ai`() {
        val snapshot = scorer.score(
            insertedLines = 40,
            deletedLines = 2,
            timestampEpochMillis = 1_000L,
        )

        assertEquals(ChangeClassification.ESTIMATED_AI_GENERATED, snapshot.classification)
        assertTrue(snapshot.estimatedAiLines > snapshot.estimatedNonAiLines)
    }

    @Test
    fun `small edits trend toward estimated non ai`() {
        val snapshot = scorer.score(
            insertedLines = 4,
            deletedLines = 1,
            timestampEpochMillis = 1_000L,
        )

        assertEquals(ChangeClassification.ESTIMATED_NON_AI, snapshot.classification)
    }

    @Test
    fun `contiguous twelve-line block trends toward estimated ai`() {
        val snapshot = scorer.score(
            insertedLines = 12,
            deletedLines = 0,
            timestampEpochMillis = 1_000L,
            largestInsertedBlock = 12,
            context = HeuristicContext(currentSource = EventSource.EXTERNAL_FILE_CHANGE),
        )

        assertEquals(ChangeClassification.ESTIMATED_AI_GENERATED, snapshot.classification)
        assertTrue(snapshot.estimatedAiLines >= 7)
    }

    @Test
    fun `document save burst is not automatically treated as ai`() {
        val snapshot = scorer.score(
            insertedLines = 12,
            deletedLines = 0,
            timestampEpochMillis = 1_000L,
            largestInsertedBlock = 12,
            context = HeuristicContext(currentSource = EventSource.DOCUMENT_SAVE, millisSincePreviousEvent = 5_000L),
        )

        assertTrue(snapshot.classification != ChangeClassification.ESTIMATED_AI_GENERATED)
    }
}
