package com.flowmetric.desktop.persistence

import com.flowmetric.shared.model.GitFileDelta
import com.flowmetric.shared.model.GitFileObservation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class GitObservationStore(
    private val storePath: Path,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
    fun read(): List<GitFileObservation> {
        if (!storePath.exists()) return emptyList()
        return runCatching { json.decodeFromString<GitObservationSnapshot>(storePath.readText()).observations }
            .map { normalize(it) }
            .getOrDefault(emptyList())
    }

    fun merge(currentFiles: List<GitFileDelta>): List<GitFileObservation> {
        val existing = read().toMutableList()
        val now = System.currentTimeMillis()
        val currentBucket = bucketStart(now)

        currentFiles.forEach { file ->
            val sameBucketIndex = existing.indexOfLast {
                it.filePath == file.filePath && bucketStart(it.observedAtEpochMillis) == currentBucket
            }

            val candidate = GitFileObservation(
                id = existing.getOrNull(sameBucketIndex)?.id ?: UUID.randomUUID().toString(),
                filePath = file.filePath,
                insertedLines = file.insertedLines,
                deletedLines = file.deletedLines,
                status = file.status,
                observedAtEpochMillis = now,
                fileModifiedEpochMillis = file.lastModifiedEpochMillis,
            )

            if (sameBucketIndex >= 0) {
                val existingObservation = existing[sameBucketIndex]
                val sameSignature =
                    existingObservation.insertedLines == candidate.insertedLines &&
                        existingObservation.deletedLines == candidate.deletedLines &&
                        existingObservation.status == candidate.status &&
                        existingObservation.fileModifiedEpochMillis == candidate.fileModifiedEpochMillis

                if (!sameSignature) {
                    existing[sameBucketIndex] = candidate
                }
            } else {
                existing += candidate
            }
        }

        val trimmed = normalize(existing)
            .sortedByDescending { it.observedAtEpochMillis }
            .take(MAX_OBSERVATIONS)

        write(trimmed)
        return trimmed
    }

    private fun normalize(observations: List<GitFileObservation>): List<GitFileObservation> =
        observations
            .sortedByDescending { it.observedAtEpochMillis }
            .groupBy { it.filePath to bucketStart(it.observedAtEpochMillis) }
            .map { (_, groupedObservations) ->
                groupedObservations.maxWith(
                    compareBy<GitFileObservation> { it.observedAtEpochMillis }
                        .thenBy { it.fileModifiedEpochMillis ?: 0L },
                )
            }
            .sortedByDescending { it.observedAtEpochMillis }

    private fun bucketStart(epochMillis: Long): Long =
        epochMillis - (epochMillis % OBSERVATION_BUCKET_MS)

    private fun write(observations: List<GitFileObservation>) {
        storePath.parent?.createDirectories()
        storePath.writeText(json.encodeToString(GitObservationSnapshot(observations)))
    }

    companion object {
        private const val MAX_OBSERVATIONS = 400
        private const val OBSERVATION_BUCKET_MS = 2 * 60 * 1000L

        fun projectStore(projectRoot: Path): GitObservationStore {
            val flowMetricDir = projectRoot.resolve(".flowmetric")
            Files.createDirectories(flowMetricDir)
            return GitObservationStore(flowMetricDir.resolve("git-observations.json"))
        }
    }
}

@Serializable
private data class GitObservationSnapshot(
    val observations: List<GitFileObservation> = emptyList(),
)
