package com.flowmetric.shared.persistence

import com.flowmetric.shared.model.ChangeEvent
import com.flowmetric.shared.model.ChangeClassification
import com.flowmetric.shared.model.EventSource
import com.flowmetric.shared.model.FlowMetricSnapshot
import com.flowmetric.shared.model.TrackedProject
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FlowMetricStore(
    private val storePath: Path,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
    fun read(): FlowMetricSnapshot {
        if (!storePath.exists()) {
            return FlowMetricSnapshot()
        }
        return json.decodeFromString<FlowMetricSnapshot>(storePath.readText())
    }

    fun write(snapshot: FlowMetricSnapshot) {
        storePath.parent?.createDirectories()
        storePath.writeText(json.encodeToString(snapshot.normalizedForDuplicateEvents()))
    }

    fun appendEvent(event: ChangeEvent, project: TrackedProject) {
        val current = read()
        val projects = (current.projects + project).distinctBy { it.id }
        val events = current.events + event
        write(FlowMetricSnapshot(projects = projects, events = events))
    }

    fun appendEvents(events: List<ChangeEvent>, projects: List<TrackedProject>) {
        if (events.isEmpty()) return

        val current = read()
        val mergedProjects = (current.projects + projects).distinctBy { it.id }
        write(
            FlowMetricSnapshot(
                projects = mergedProjects,
                events = current.events + events,
            ),
        )
    }

    companion object {
        fun projectStore(projectRoot: Path): FlowMetricStore {
            val flowMetricDir = projectRoot.resolve(".flowmetric")
            Files.createDirectories(flowMetricDir)
            return FlowMetricStore(flowMetricDir.resolve("events.json"))
        }
    }
}

private fun FlowMetricSnapshot.normalizedForDuplicateEvents(): FlowMetricSnapshot =
    copy(events = events.removeInferiorDuplicateEvents())

private fun List<ChangeEvent>.removeInferiorDuplicateEvents(): List<ChangeEvent> {
    val bestEventIdByGroup = groupBy { it.duplicateGroupKey() }
        .mapNotNull { (groupKey, duplicateGroup) ->
            groupKey?.let { duplicateGroup.maxWithOrNull(changeEventPreferenceComparator())?.id }
        }
        .toSet()

    if (bestEventIdByGroup.isEmpty()) {
        return this
    }

    return filter { event ->
        event.duplicateGroupKey() == null || event.id in bestEventIdByGroup
    }
}

private fun ChangeEvent.duplicateGroupKey(): String? =
    metadata.linePatch
        ?.takeIf { it.isNotBlank() }
        ?.let { "$filePath\u0000$it\u0000$sessionId" }

private fun changeEventPreferenceComparator(): Comparator<ChangeEvent> =
    compareBy<ChangeEvent>(
        { it.metadata.source.preferenceRank() },
        { it.snapshot.classification.preferenceRank() },
        { it.snapshot.confidence.preferenceRank() },
        { it.timestampEpochMillis },
    )

private fun EventSource.preferenceRank(): Int = when (this) {
    EventSource.EXTERNAL_FILE_CHANGE -> 0
    EventSource.DOCUMENT_SAVE -> 1
    EventSource.MANUAL_IMPORT -> 2
    EventSource.CODEX_PATCH -> 3
    EventSource.AI_PATCH -> 3
}

private fun ChangeClassification.preferenceRank(): Int = when (this) {
    ChangeClassification.ESTIMATED_NON_AI -> 0
    ChangeClassification.MIXED_OR_UNCLEAR -> 1
    ChangeClassification.ESTIMATED_AI_GENERATED -> 2
}

private fun com.flowmetric.shared.model.ConfidenceLevel.preferenceRank(): Int = when (this) {
    com.flowmetric.shared.model.ConfidenceLevel.LOW -> 0
    com.flowmetric.shared.model.ConfidenceLevel.MEDIUM -> 1
    com.flowmetric.shared.model.ConfidenceLevel.HIGH -> 2
}
