package com.flowmetric.plugin.actions

import com.flowmetric.plugin.services.FlowMetricProjectService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.ui.Messages

class SelectTrackedProjectAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val chooser = FileChooserFactory.getInstance()
            .createPathChooser(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null)
        // Seed the chooser from the current project so switching the tracked root stays local to the IDE context.
        chooser.choose(project.baseDir) { selected ->
            val path = selected.firstOrNull()?.path ?: return@choose
            // The project service owns the tracked-root state and persistence used by document/VFS listeners.
            project.getService(FlowMetricProjectService::class.java).updateTrackedRoot(path)
            Messages.showInfoMessage(project, "FlowMetric will track saved changes under:\n$path", "FlowMetric")
        }
    }
}
