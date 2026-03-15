package com.flowmetric.desktop.install

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Represents the result of a tracking installation attempt, indicating success or failure
 * and providing a descriptive message.
 *
 * @param success True if the installation was successful, false otherwise.
 * @param message A string describing the outcome of the installation.
 */
data class TrackingInstallResult(
    val success: Boolean,
    val message: String,
)

/**
 * Provides a status overview of whether a project has the necessary FlowMetric tracking
 * files and configurations in place.
 *
 * @param projectExists True if the specified project root directory exists.
 * @param agentsFileExists True if the AGENTS.md file exists in the project root.
 * @param editScriptExists True if the `record_codex_edit.sh` script exists in the 'scripts' directory.
 * @param batchScriptExists True if the `record_codex_batch.sh` script exists in the 'scripts' directory.
 */
data class TrackingProjectStatus(
    val projectExists: Boolean,
    val agentsFileExists: Boolean,
    val editScriptExists: Boolean,
    val batchScriptExists: Boolean,
) {
    /**
     * Convenience property to check if all essential tracking files are present.
     */
    val allRequiredFilesPresent: Boolean
        get() = projectExists && agentsFileExists && editScriptExists && batchScriptExists
}

/**
 * Manages the installation and verification of FlowMetric's tracking mechanisms
 * within a user's project directory. This includes checking for existing files,
 * creating necessary scripts, and updating the AGENTS.md file.
 *
 * @param flowMetricRoot The root directory of the FlowMetric application itself,
 *                       used to locate source scripts. Defaults to the current
 *                       working directory if not specified.
 */
class TrackingInstaller(
    private val flowMetricRoot: Path = Path.of("").toAbsolutePath().normalize(),
) {
    /**
     * Inspects a given project root to determine the presence of FlowMetric's
     * tracking-related files.
     *
     * @param projectRoot The root directory of the project to inspect.
     * @return A [TrackingProjectStatus] object indicating which tracking files are present.
     */
    fun inspectTracking(projectRoot: Path): TrackingProjectStatus {
        val normalizedProjectRoot = projectRoot.toAbsolutePath().normalize()
        val scriptsDir = normalizedProjectRoot.resolve("scripts")
        return TrackingProjectStatus(
            projectExists = normalizedProjectRoot.exists(),
            agentsFileExists = normalizedProjectRoot.resolve("AGENTS.md").exists(),
            editScriptExists = scriptsDir.resolve("record_codex_edit.sh").exists(),
            batchScriptExists = scriptsDir.resolve("record_codex_batch.sh").exists(),
        )
    }

    /**
     * Installs or updates the FlowMetric tracking files within a specified project.
     * This involves creating proxy scripts and modifying the AGENTS.md file to
     * include instructions for Codex tracking.
     *
     * @param projectRoot The root directory of the project where tracking should be installed.
     * @param sourceLabel A label identifying the source of the agent (e.g., "gpt-4").
     * @param agentModel An optional string indicating the specific agent model used.
     * @return A [TrackingInstallResult] indicating the success or failure of the installation.
     */
    fun installTracking(projectRoot: Path, sourceLabel: String, agentModel: String?): TrackingInstallResult {
        if (!projectRoot.exists()) {
            return TrackingInstallResult(false, "Selected project does not exist.")
        }

        return runCatching {
            val normalizedProjectRoot = projectRoot.toAbsolutePath().normalize()
            val scriptsDir = normalizedProjectRoot.resolve("scripts").createDirectories()
            val editScriptPath = scriptsDir.resolve("record_codex_edit.sh")
            val batchScriptPath = scriptsDir.resolve("record_codex_batch.sh")
            val projectAgentsPath = normalizedProjectRoot.resolve("AGENTS.md")

            writeProxyScript(editScriptPath, "record_codex_edit.sh")
            writeProxyScript(batchScriptPath, "record_codex_batch.sh")
            updateAgentsFile(projectAgentsPath, sourceLabel, agentModel)

            TrackingInstallResult(
                success = true,
                message = "Tracking files are ready in `${normalizedProjectRoot.fileName}`.",
            )
        }.getOrElse { error ->
            TrackingInstallResult(false, "Failed to prepare tracking files: ${error.message ?: "unknown error"}")
        }
    }

    /**
     * Writes a proxy shell script to the target path that delegates to an actual
     * FlowMetric script located in the `flowMetricRoot`.
     *
     * @param targetScriptPath The path where the proxy script should be written.
     * @param sourceScriptName The name of the source script within the FlowMetric root's 'scripts' directory.
     */
    private fun writeProxyScript(targetScriptPath: Path, sourceScriptName: String) {
        val sourceScript = flowMetricRoot.resolve("scripts").resolve(sourceScriptName).toAbsolutePath().normalize()
        val scriptBody = """
            |#!/usr/bin/env bash
            |set -euo pipefail
            |
            |FLOWMETRIC_SCRIPT="${sourceScript.toString().escapeForDoubleQuotes()}"
            |
            |"${'$'}FLOWMETRIC_SCRIPT" "${'$'}@"
        """.trimMargin() + "\n"

        targetScriptPath.writeText(scriptBody)
        targetScriptPath.toFile().setExecutable(true)
    }

    /**
     * Updates the `AGENTS.md` file in the project to include or update the
     * FlowMetric Agent Tracking section.
     *
     * @param projectAgentsPath The path to the AGENTS.md file.
     * @param sourceLabel A label identifying the source of the agent.
     * @param agentModel An optional string indicating the specific agent model used.
     */
    private fun updateAgentsFile(projectAgentsPath: Path, sourceLabel: String, agentModel: String?) {
        val existing = if (projectAgentsPath.exists()) {
            projectAgentsPath.readText()
        } else {
            "# AGENTS.md\n\n"
        }

        val updated = when {
            existing.contains(START_MARKER) && existing.contains(END_MARKER) -> {
                val startIndex = existing.indexOf(START_MARKER)
                val endIndex = existing.indexOf(END_MARKER) + END_MARKER.length
                existing.replaceRange(startIndex, endIndex, agentTrackingSection(sourceLabel, agentModel))
            }
            existing.endsWith("\n") -> existing + "\n" + agentTrackingSection(sourceLabel, agentModel)
            else -> existing + "\n\n" + agentTrackingSection(sourceLabel, agentModel)
        }

        projectAgentsPath.writeText(updated)
    }

    /**
     * Generates the markdown string for the FlowMetric Agent Tracking section
     * to be inserted into the AGENTS.md file.
     *
     * @param sourceLabel A label identifying the source of the agent.
     * @param agentModel An optional string indicating the specific agent model used.
     * @return The formatted markdown string for the tracking section.
     */
    private fun agentTrackingSection(sourceLabel: String, agentModel: String?): String {
        val normalizedSourceLabel = sourceLabel.trim().ifBlank { "Agent" }
        val modelGuidance = agentModel
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { """- Pass the model explicitly as the last argument so different events can use different models.
- Example model for $normalizedSourceLabel:
  `$it`""" }
            ?: ""
        val editCommand = buildString {
            append("`scripts/record_codex_edit.sh /absolute/path/to/project-root /absolute/path/to/file \"\${'$'}tmp_before\" $normalizedSourceLabel")
            agentModel
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { append(" $it") }
            append("`")
        }
        val batchCommand = buildString {
            append("`scripts/record_codex_batch.sh /absolute/path/to/project-root /tmp/flowmetric-agent-manifest.tsv $normalizedSourceLabel")
            agentModel
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { append(" $it") }
            append("`")
        }

        return """
        |$START_MARKER
        |## FlowMetric Agent Tracking
        |- When $normalizedSourceLabel edits a tracked project file, record the exact patch so FlowMetric can attribute those lines.
        |- Before the agent edits a file, save a temp snapshot:
        |  `tmp_before="${'$'}(mktemp)"; cp /absolute/path/to/file "${'$'}tmp_before"`
        |$modelGuidance
        |- After the agent finishes editing, run:
        |  $editCommand
        |- For one-shot batch recording across many files, create a tab-separated manifest:
        |  `printf '%s\t%s\n' /absolute/path/to/file "${'$'}tmp_before" > /tmp/flowmetric-agent-manifest.tsv`
        |  $batchCommand
        |- Keep the file paths absolute so FlowMetric can resolve the patch correctly.
        |$END_MARKER
    """.trimMargin()
    }

    /**
     * Escapes special characters in a string for safe inclusion within double quotes
     * in a shell script.
     *
     * @return The escaped string.
     */
    private fun String.escapeForDoubleQuotes(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val START_MARKER = "<!-- FLOWMETRIC CODEX TRACKING START -->"
        private const val END_MARKER = "<!-- FLOWMETRIC CODEX TRACKING END -->"
    }
}
