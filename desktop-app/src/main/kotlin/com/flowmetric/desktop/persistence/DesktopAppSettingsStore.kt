package com.flowmetric.desktop.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
enum class DesktopThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
enum class StartupTabPreference {
    GIT,
    EVENTS,
}

@Serializable
data class DesktopAppSettings(
    val theme: DesktopThemePreference = DesktopThemePreference.SYSTEM,
    val reopenLastProjectOnLaunch: Boolean = true,
    val refreshAutomaticallyOnWatchedChanges: Boolean = true,
    val defaultTab: StartupTabPreference = StartupTabPreference.GIT,
    val defaultLookbackDays: Int = 7,
) {
    fun normalized(): DesktopAppSettings = copy(
        defaultLookbackDays = defaultLookbackDays.takeIf { it in SUPPORTED_LOOKBACK_DAYS } ?: DEFAULT_LOOKBACK_DAYS,
    )

    companion object {
        val SUPPORTED_LOOKBACK_DAYS = setOf(3, 7, 14, 30)
        const val DEFAULT_LOOKBACK_DAYS = 7
    }
}

class DesktopAppSettingsStore(
    private val storePath: Path = defaultStorePath(),
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
    fun read(): DesktopAppSettings {
        if (!storePath.exists()) {
            val defaultSettings = DesktopAppSettings()
            write(defaultSettings)
            return defaultSettings
        }

        return runCatching {
            json.decodeFromString<DesktopAppSettings>(storePath.readText()).normalized()
        }.getOrElse {
            val defaultSettings = DesktopAppSettings()
            write(defaultSettings)
            defaultSettings
        }
    }

    fun write(settings: DesktopAppSettings) {
        storePath.parent?.createDirectories()
        storePath.writeText(json.encodeToString(settings.normalized()))
    }

    companion object {
        private fun defaultStorePath(): Path {
            val userHome = System.getProperty("user.home")
            return Path.of(userHome, ".flowmetric-desktop", "app-settings.json")
        }
    }
}
