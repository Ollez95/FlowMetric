package com.flowmetric.shared.persistence

import com.flowmetric.shared.model.ChangeClassification
import com.flowmetric.shared.model.ChangeEvent
import com.flowmetric.shared.model.ChangeMetadata
import com.flowmetric.shared.model.ConfidenceLevel
import com.flowmetric.shared.model.EventSource
import com.flowmetric.shared.model.FileLineDelta
import com.flowmetric.shared.model.FlowMetricSnapshot
import com.flowmetric.shared.model.HeuristicSnapshot
import com.flowmetric.shared.model.TrackedProject
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowMetricStoreTest {
    @Test
    fun `write removes non-ai duplicate when matching ai event exists`() {
        val store = FlowMetricStore(createTempFile())
        val project = TrackedProject(
            id = "/tmp/project",
            rootPath = "/tmp/project",
            selectedAtEpochMillis = 1_000L,
        )
        val nonAiEvent = changeEvent(
            id = "non-ai",
            classification = ChangeClassification.ESTIMATED_NON_AI,
            source = EventSource.EXTERNAL_FILE_CHANGE,
        )
        val aiEvent = changeEvent(
            id = "ai",
            classification = ChangeClassification.ESTIMATED_AI_GENERATED,
            source = EventSource.AI_PATCH,
        )

        store.write(
            FlowMetricSnapshot(
                projects = listOf(project),
                events = listOf(nonAiEvent, aiEvent),
            ),
        )

        val storedEvents = store.read().events
        assertEquals(listOf("ai"), storedEvents.map { it.id })
    }

    @Test
    fun `write keeps non-ai event when no matching ai duplicate exists`() {
        val store = FlowMetricStore(createTempFile())
        val project = TrackedProject(
            id = "/tmp/project",
            rootPath = "/tmp/project",
            selectedAtEpochMillis = 1_000L,
        )
        val nonAiEvent = changeEvent(
            id = "non-ai",
            classification = ChangeClassification.ESTIMATED_NON_AI,
            source = EventSource.EXTERNAL_FILE_CHANGE,
            patch = "@@ -1,1 +1,1 @@\n-old\n+new",
            latestContentHash = "hash-a",
        )
        val aiEvent = changeEvent(
            id = "ai",
            classification = ChangeClassification.ESTIMATED_AI_GENERATED,
            source = EventSource.AI_PATCH,
            patch = "@@ -2,1 +2,1 @@\n-old\n+newer",
            latestContentHash = "hash-b",
        )

        store.write(
            FlowMetricSnapshot(
                projects = listOf(project),
                events = listOf(nonAiEvent, aiEvent),
            ),
        )

        val storedEvents = store.read().events
        assertEquals(listOf("non-ai", "ai"), storedEvents.map { it.id })
    }

    @Test
    fun `write removes external duplicate when ai patch has same file patch and session but different hash`() {
        val store = FlowMetricStore(createTempFile())
        val project = TrackedProject(
            id = "/tmp/project",
            rootPath = "/tmp/project",
            selectedAtEpochMillis = 1_000L,
        )
        val externalEvent = changeEvent(
            id = "external",
            classification = ChangeClassification.ESTIMATED_NON_AI,
            source = EventSource.EXTERNAL_FILE_CHANGE,
            latestContentHash = "hash-a",
            sessionId = "session-1",
        )
        val aiPatchEvent = changeEvent(
            id = "ai-patch",
            classification = ChangeClassification.ESTIMATED_AI_GENERATED,
            source = EventSource.AI_PATCH,
            latestContentHash = "hash-b",
            sessionId = "session-1",
        )

        store.write(
            FlowMetricSnapshot(
                projects = listOf(project),
                events = listOf(externalEvent, aiPatchEvent),
            ),
        )

        val storedEvents = store.read().events
        assertEquals(listOf("ai-patch"), storedEvents.map { it.id })
    }

    @Test
    fun `write keeps same patch in different sessions`() {
        val store = FlowMetricStore(createTempFile())
        val project = TrackedProject(
            id = "/tmp/project",
            rootPath = "/tmp/project",
            selectedAtEpochMillis = 1_000L,
        )
        val oldExternalEvent = changeEvent(
            id = "external-old",
            classification = ChangeClassification.ESTIMATED_NON_AI,
            source = EventSource.EXTERNAL_FILE_CHANGE,
            sessionId = "session-1",
        )
        val newAiPatchEvent = changeEvent(
            id = "ai-patch-new",
            classification = ChangeClassification.ESTIMATED_AI_GENERATED,
            source = EventSource.AI_PATCH,
            sessionId = "session-2",
        )

        store.write(
            FlowMetricSnapshot(
                projects = listOf(project),
                events = listOf(oldExternalEvent, newAiPatchEvent),
            ),
        )

        val storedEvents = store.read().events
        assertEquals(listOf("external-old", "ai-patch-new"), storedEvents.map { it.id })
    }

    @Test
    fun `write keeps only latest event when duplicate group contains only external changes`() {
        val store = FlowMetricStore(createTempFile())
        val project = TrackedProject(
            id = "/tmp/project",
            rootPath = "/tmp/project",
            selectedAtEpochMillis = 1_000L,
        )
        val olderExternalEvent = changeEvent(
            id = "external-old",
            classification = ChangeClassification.ESTIMATED_NON_AI,
            source = EventSource.EXTERNAL_FILE_CHANGE,
            timestampEpochMillis = 1_000L,
            sessionId = "session-1",
        )
        val newerExternalEvent = changeEvent(
            id = "external-new",
            classification = ChangeClassification.ESTIMATED_NON_AI,
            source = EventSource.EXTERNAL_FILE_CHANGE,
            timestampEpochMillis = 2_000L,
            sessionId = "session-1",
        )

        store.write(
            FlowMetricSnapshot(
                projects = listOf(project),
                events = listOf(olderExternalEvent, newerExternalEvent),
            ),
        )

        val storedEvents = store.read().events
        assertEquals(listOf("external-new"), storedEvents.map { it.id })
    }

    private fun changeEvent(
        id: String,
        classification: ChangeClassification,
        source: EventSource,
        patch: String = "@@ -1,1 +1,1 @@\n-old\n+new",
        latestContentHash: String = "same-hash",
        sessionId: String = "session-1",
        timestampEpochMillis: Long = 1_000L,
    ): ChangeEvent = ChangeEvent(
        id = id,
        projectId = "/tmp/project",
        projectPath = "/tmp/project",
        filePath = "/tmp/project/src/Main.kt",
        timestampEpochMillis = timestampEpochMillis,
        sessionId = sessionId,
        delta = FileLineDelta(inserted = 1, deleted = 1, largestInsertedBlock = 1, largestDeletedBlock = 1),
        metadata = ChangeMetadata(
            source = source,
            fileExtension = "kt",
            latestContentHash = latestContentHash,
            linePatch = patch,
        ),
        snapshot = HeuristicSnapshot(
            classification = classification,
            confidence = ConfidenceLevel.HIGH,
            confidenceScore = 1.0,
            estimatedAiLines = if (classification == ChangeClassification.ESTIMATED_AI_GENERATED) 2 else 0,
            estimatedNonAiLines = if (classification == ChangeClassification.ESTIMATED_NON_AI) 2 else 0,
            matchedSignals = emptyList(),
            notes = "",
        ),
    )
}
