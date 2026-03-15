package com.flowmetric.plugin.state

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "FlowMetricAppState", storages = [Storage("flowmetric.xml")])
class FlowMetricAppState : SerializablePersistentStateComponent<FlowMetricAppState.State>(State()) {
    data class State(
        // Persist the chosen FlowMetric root per IDE project so save events can resolve consistently.
        val selectedProjectRoots: Map<String, String> = emptyMap(),
    )

    fun selectedRoot(projectLocationHash: String): String? = state.selectedProjectRoots[projectLocationHash]

    fun updateSelectedRoot(projectLocationHash: String, rootPath: String) {
        updateState { current ->
            current.copy(selectedProjectRoots = current.selectedProjectRoots + (projectLocationHash to rootPath))
        }
    }
}
