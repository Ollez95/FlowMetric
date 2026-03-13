package com.flowmetric.shared.analytics

import com.flowmetric.shared.model.AnalyticsFilter
import com.flowmetric.shared.model.ChangeClassification
import com.flowmetric.shared.model.ChangeEvent
import com.flowmetric.shared.model.ConfidenceLevel
import com.flowmetric.shared.model.DashboardMetrics
import com.flowmetric.shared.model.FileEstimate
import com.flowmetric.shared.model.SessionSummary
import com.flowmetric.shared.model.TrendPoint
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.math.roundToInt

class AnalyticsEngine(
    private val supportedExtensions: Set<String> = setOf(
        "kt", "kts", "java", "xml", "gradle", "md", "js", "ts", "tsx", "jsx", "json", "yml", "yaml",
    ),
) {
    fun buildDashboard(
        events: List<ChangeEvent>,
        filter: AnalyticsFilter,
        totalProjectLines: Int = countProjectLines(Path.of(filter.projectPath)),
    ): DashboardMetrics {
        val filteredEvents = events
            .filter { it.projectPath == filter.projectPath }
            .filter { filter.fromEpochMillis == null || it.timestampEpochMillis >= filter.fromEpochMillis }
            .filter { filter.toEpochMillis == null || it.timestampEpochMillis <= filter.toEpochMillis }
            .filter { it.snapshot.confidence in filter.confidence }
            .sortedByDescending { it.timestampEpochMillis }

        val changedLines = filteredEvents.sumOf { it.delta.changedLines }
        val estimatedAiLines = filteredEvents.sumOf { it.snapshot.estimatedAiLines }
        val estimatedNonAiLines = filteredEvents.sumOf { it.snapshot.estimatedNonAiLines }
        val totalEstimated = (estimatedAiLines + estimatedNonAiLines).coerceAtLeast(1)
        val files = buildFileEstimates(filteredEvents)
        val sessions = buildSessionSummaries(filteredEvents)
        val trends = buildTrend(filteredEvents)

        return DashboardMetrics(
            totalProjectLines = totalProjectLines,
            changedLines = changedLines,
            estimatedAiLines = estimatedAiLines,
            estimatedNonAiLines = estimatedNonAiLines,
            aiPercentage = (estimatedAiLines.toDouble() / totalEstimated * 100.0).roundToInt().toDouble(),
            nonAiPercentage = (estimatedNonAiLines.toDouble() / totalEstimated * 100.0).roundToInt().toDouble(),
            files = files,
            sessions = sessions,
            trends = trends,
        )
    }

    private fun buildFileEstimates(events: List<ChangeEvent>): List<FileEstimate> =
        events.groupBy { it.filePath }
            .map { (filePath, fileEvents) ->
                val latest = fileEvents.maxBy { it.timestampEpochMillis }
                val changedLines = fileEvents.sumOf { it.delta.changedLines }
                val estimatedAiLines = fileEvents.sumOf { it.snapshot.estimatedAiLines }
                val estimatedNonAiLines = fileEvents.sumOf { it.snapshot.estimatedNonAiLines }
                val avgConfidence = fileEvents.map { it.snapshot.confidenceScore }.average()
                FileEstimate(
                    filePath = filePath,
                    changedLines = changedLines,
                    estimatedAiLines = estimatedAiLines,
                    estimatedNonAiLines = estimatedNonAiLines,
                    classification = dominantClassification(fileEvents),
                    confidence = confidenceFromScore(avgConfidence),
                    latestTimestampEpochMillis = latest.timestampEpochMillis,
                    sessionCount = fileEvents.map { it.sessionId }.distinct().size,
                )
            }
            .sortedByDescending { it.latestTimestampEpochMillis }

    private fun buildSessionSummaries(events: List<ChangeEvent>): List<SessionSummary> =
        events.groupBy { it.sessionId }
            .map { (sessionId, sessionEvents) ->
                val first = sessionEvents.minBy { it.timestampEpochMillis }
                val latest = sessionEvents.maxBy { it.timestampEpochMillis }
                val avgConfidence = sessionEvents.map { it.snapshot.confidenceScore }.average()
                SessionSummary(
                    sessionId = sessionId,
                    projectId = first.projectId,
                    startedAtEpochMillis = first.timestampEpochMillis,
                    endedAtEpochMillis = latest.timestampEpochMillis,
                    fileCount = sessionEvents.map { it.filePath }.distinct().size,
                    totalInserted = sessionEvents.sumOf { it.delta.inserted },
                    totalDeleted = sessionEvents.sumOf { it.delta.deleted },
                    classification = dominantClassification(sessionEvents),
                    confidence = confidenceFromScore(avgConfidence),
                )
            }
            .sortedByDescending { it.endedAtEpochMillis }

    private fun buildTrend(events: List<ChangeEvent>): List<TrendPoint> {
        val formatter = DateTimeFormatter.ofPattern("MM-dd").withZone(ZoneId.systemDefault())
        return events.groupBy { formatter.format(Instant.ofEpochMilli(it.timestampEpochMillis)) }
            .map { (dayLabel, dayEvents) ->
                TrendPoint(
                    dayLabel = dayLabel,
                    estimatedAiLines = dayEvents.sumOf { it.snapshot.estimatedAiLines },
                    estimatedNonAiLines = dayEvents.sumOf { it.snapshot.estimatedNonAiLines },
                )
            }
            .sortedBy { it.dayLabel }
    }

    private fun dominantClassification(events: List<ChangeEvent>): ChangeClassification {
        val ai = events.count { it.snapshot.classification == ChangeClassification.ESTIMATED_AI_GENERATED }
        val nonAi = events.count { it.snapshot.classification == ChangeClassification.ESTIMATED_NON_AI }
        return when {
            ai > nonAi -> ChangeClassification.ESTIMATED_AI_GENERATED
            nonAi > ai -> ChangeClassification.ESTIMATED_NON_AI
            else -> ChangeClassification.MIXED_OR_UNCLEAR
        }
    }

    private fun confidenceFromScore(score: Double): ConfidenceLevel = when {
        score >= 0.75 -> ConfidenceLevel.HIGH
        score >= 0.5 -> ConfidenceLevel.MEDIUM
        else -> ConfidenceLevel.LOW
    }

    fun countProjectLines(projectRoot: Path): Int {
        if (!Files.exists(projectRoot)) return 0
        Files.walk(projectRoot).use { paths ->
            return paths.iterator().asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { !it.toString().contains("${Path.of(".flowmetric")}") }
                .filter { !it.toString().contains("${Path.of("build")}") }
                .filter { it.extension.lowercase() in supportedExtensions || it.name == "Dockerfile" }
                .map { path -> runCatching { countLines(path) }.getOrDefault(0) }
                .sum()
        }
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
}
