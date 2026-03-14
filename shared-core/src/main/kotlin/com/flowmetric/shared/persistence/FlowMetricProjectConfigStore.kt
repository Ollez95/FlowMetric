package com.flowmetric.shared.persistence

import com.flowmetric.shared.model.FlowMetricProjectConfig
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FlowMetricProjectConfigStore(
    private val configPath: Path,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
    fun readOrCreate(): FlowMetricProjectConfig {
        if (!configPath.exists()) {
            val defaultConfig = FlowMetricProjectConfig()
            write(defaultConfig)
            return defaultConfig
        }
        return runCatching { json.decodeFromString<FlowMetricProjectConfig>(configPath.readText()) }
            .getOrElse {
                val defaultConfig = FlowMetricProjectConfig()
                write(defaultConfig)
                defaultConfig
            }
    }

    fun write(config: FlowMetricProjectConfig) {
        configPath.parent?.createDirectories()
        configPath.writeText(json.encodeToString(config))
    }

    companion object {
        fun projectConfigStore(projectRoot: Path): FlowMetricProjectConfigStore {
            val flowMetricDir = projectRoot.resolve(".flowmetric")
            Files.createDirectories(flowMetricDir)
            return FlowMetricProjectConfigStore(flowMetricDir.resolve("config.json"))
        }
    }
}
