package com.flowmetric.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.flowmetric.desktop.persistence.DesktopThemePreference

internal val FlowMetricOrange = Color(0xFFD96C2F)
internal val FlowMetricTeal = Color(0xFF2A7F62)
internal val FlowMetricSlate = Color(0xFF49616D)
internal val FlowMetricPlum = Color(0xFF7A3E65)
internal val FlowMetricGold = Color(0xFFB7791F)
internal val FlowMetricInk = Color(0xFF172A3A)

private val LightFlowMetricColorScheme = lightColorScheme(
    primary = FlowMetricOrange,
    onPrimary = Color.White,
    secondary = FlowMetricTeal,
    onSecondary = Color.White,
    tertiary = FlowMetricPlum,
    onTertiary = Color.White,
    background = Color(0xFFF2EFE8),
    onBackground = FlowMetricInk,
    surface = Color(0xFFFFFBF5),
    onSurface = FlowMetricInk,
    surfaceVariant = Color(0xFFE7E0D4),
    onSurfaceVariant = FlowMetricSlate,
    outline = Color(0xFFC2B7A3),
)

private val DarkFlowMetricColorScheme = darkColorScheme(
    primary = Color(0xFFF0A260),
    onPrimary = Color(0xFF42210A),
    secondary = Color(0xFF7AC5A7),
    onSecondary = Color(0xFF103628),
    tertiary = Color(0xFFD7A4C6),
    onTertiary = Color(0xFF442238),
    background = Color(0xFF12171A),
    onBackground = Color(0xFFF2EFE8),
    surface = Color(0xFF1A2024),
    onSurface = Color(0xFFF2EFE8),
    surfaceVariant = Color(0xFF31383D),
    onSurfaceVariant = Color(0xFFB8C2C8),
    outline = Color(0xFF728089),
)

@Composable
internal fun FlowMetricTheme(
    themePreference: DesktopThemePreference,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themePreference) {
        DesktopThemePreference.SYSTEM -> isSystemInDarkTheme()
        DesktopThemePreference.LIGHT -> false
        DesktopThemePreference.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkFlowMetricColorScheme else LightFlowMetricColorScheme,
        content = content,
    )
}
