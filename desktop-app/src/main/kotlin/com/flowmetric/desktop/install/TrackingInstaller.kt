package com.flowmetric.desktop.install

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class TrackingInstallResult(
    val success: Boolean,
    val message: String,
)

class TrackingInstaller(
    private val flowMetricRoot: Path = Path.of("").toAbsolutePath().normalize(),
) {
    fun installCodexTracking(projectRoot: Path): TrackingInstallResult {
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
            updateAgentsFile(projectAgentsPath)

            TrackingInstallResult(
                success = true,
                message = "Installed Codex tracking in `${normalizedProjectRoot.fileName}`.",
            )
        }.getOrElse { error ->
            TrackingInstallResult(false, "Failed to install Codex tracking: ${error.message ?: "unknown error"}")
        }
    }

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

    private fun updateAgentsFile(projectAgentsPath: Path) {
        val existing = if (projectAgentsPath.exists()) {
            projectAgentsPath.readText()
        } else {
            "# AGENTS.md\n\n"
        }

        val updated = when {
            existing.contains(START_MARKER) && existing.contains(END_MARKER) -> {
                val startIndex = existing.indexOf(START_MARKER)
                val endIndex = existing.indexOf(END_MARKER) + END_MARKER.length
                existing.replaceRange(startIndex, endIndex, codexTrackingSection())
            }
            existing.endsWith("\n") -> existing + "\n" + codexTrackingSection()
            else -> existing + "\n\n" + codexTrackingSection()
        }

        projectAgentsPath.writeText(updated)
    }

    private fun codexTrackingSection(): String = """
        |$START_MARKER
        |## FlowMetric Codex Tracking
        |- When Codex edits a tracked project file, record the exact patch so FlowMetric can attribute those lines to Codex.
        |- Before Codex edits a file, save a temp snapshot:
        |  `tmp_before="${'$'}(mktemp)"; cp /absolute/path/to/file "${'$'}tmp_before"`
        |- After Codex finishes editing, run:
        |  `scripts/record_codex_edit.sh /absolute/path/to/project-root /absolute/path/to/file "${'$'}tmp_before" Codex`
        |- For one-shot batch recording across many files, create a tab-separated manifest:
        |  `printf '%s\t%s\n' /absolute/path/to/file "${'$'}tmp_before" > /tmp/flowmetric-codex-manifest.tsv`
        |  `scripts/record_codex_batch.sh /absolute/path/to/project-root /tmp/flowmetric-codex-manifest.tsv Codex`
        |- Keep the file paths absolute so FlowMetric can resolve the patch correctly.
        |$END_MARKER
    """.trimMargin()

    private fun String.escapeForDoubleQuotes(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val START_MARKER = "<!-- FLOWMETRIC CODEX TRACKING START -->"
        private const val END_MARKER = "<!-- FLOWMETRIC CODEX TRACKING END -->"
    }
}
