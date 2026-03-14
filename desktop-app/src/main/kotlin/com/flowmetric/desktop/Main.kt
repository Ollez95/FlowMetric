package com.flowmetric.desktop

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import com.flowmetric.desktop.ui.FlowMetricApp

fun main() = application {
    val windowState = rememberWindowState(placement = WindowPlacement.Maximized)
    Window(
        onCloseRequest = ::exitApplication,
        title = "FlowMetric",
        state = windowState,
    ) {
        FlowMetricApp()
    }
}
