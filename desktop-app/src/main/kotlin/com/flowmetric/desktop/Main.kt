package com.flowmetric.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.flowmetric.desktop.ui.FlowMetricApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "FlowMetric",
    ) {
        FlowMetricApp()
    }
}
