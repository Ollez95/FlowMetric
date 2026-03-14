package com.flowmetric.shared.config

import com.flowmetric.shared.model.FlowMetricProjectConfig
import java.nio.file.Path
import kotlin.io.path.extension

object ProjectFileRules {
    val defaultIgnoredPathFragments: List<String> = listOf(
        "/.flowmetric/",
        "/.git/",
        "/.gradle/",
        "/.idea/",
        "/build/",
        "/out/",
        "/node_modules/",
    )

    val defaultSupportedExtensions: Set<String> = setOf(
        "kt",
        "kts",
        "java",
        "xml",
        "gradle",
        "md",
        "js",
        "ts",
        "tsx",
        "jsx",
        "json",
        "yml",
        "yaml",
    )

    fun isTrackable(
        projectRoot: Path,
        path: Path,
        config: FlowMetricProjectConfig,
        supportedExtensions: Set<String> = defaultSupportedExtensions,
    ): Boolean {
        if (!path.startsWith(projectRoot)) return false

        val normalizedPath = path.toString().replace('\\', '/')
        if (ignoredPathFragments(config).any { normalizedPath.contains(it) }) return false

        if (path.fileName.toString() == "Dockerfile") return true

        val extension = path.extension.lowercase()
        if (extension.isBlank()) return false

        return extension in supportedExtensions && extension !in ignoredExtensions(config)
    }

    private fun ignoredExtensions(config: FlowMetricProjectConfig): Set<String> =
        config.ignoredExtensions
            .asSequence()
            .map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun ignoredPathFragments(config: FlowMetricProjectConfig): List<String> =
        (defaultIgnoredPathFragments + config.ignoredPathFragments)
            .asSequence()
            .map { it.trim().replace('\\', '/') }
            .filter { it.isNotBlank() }
            .toList()
}
