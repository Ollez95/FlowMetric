package com.flowmetric.plugin.state

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persists FlowMetric application-level state.
 */
@Service(Service.Level.APP)
@State(name = "FlowMetricAppState", storages = [Storage("flowmetric.xml")])
class FlowMetricAppState : SerializablePersistentStateComponent<FlowMetricAppState.State>(State()) {
    /**
     * Holds the state for [FlowMetricAppState].
     *
     * @property selectedProjectRoots A map where the key is the project location hash and the value is the selected FlowMetric root path.
     */
    data class State(
        // Persist the chosen FlowMetric root per IDE project so save events can resolve consistently.
        val selectedProjectRoots: Map<String, String> = emptyMap(),
    )

    /**
     * Retrieves the selected FlowMetric root path for a given project.
     *
     * @param projectLocationHash The hash of the project's location.
     * @return The selected root path, or null if not found.
     */
    fun selectedRoot(projectLocationHash: String): String? = state.selectedProjectRoots[projectLocationHash]

    /**
     * Updates the selected FlowMetric root path for a given project.
     *
     * @param projectLocationHash The hash of the project's location.
     * @param rootPath The new root path to be persisted.
     */
    fun updateSelectedRoot(projectLocationHash: String, rootPath: String) {
        updateState { current ->
            current.copy(selectedProjectRoots = current.selectedProjectRoots + (projectLocationHash to rootPath))
        }
    }
}
