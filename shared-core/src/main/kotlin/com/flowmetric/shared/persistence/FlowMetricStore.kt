package com.flowmetric.shared.persistence

import com.flowmetric.shared.model.ChangeEvent
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
        storePath.writeText(json.encodeToString(snapshot))
    }

    fun appendEvent(event: ChangeEvent, project: TrackedProject) {
        val current = read()
        val projects = (current.projects + project).distinctBy { it.id }
        val events = current.events + event
        write(FlowMetricSnapshot(projects = projects, events = events))
    }

    companion object {
        fun projectStore(projectRoot: Path): FlowMetricStore {
            val flowMetricDir = projectRoot.resolve(".flowmetric")
            Files.createDirectories(flowMetricDir)
            return FlowMetricStore(flowMetricDir.resolve("events.json"))
        }
    }
}
