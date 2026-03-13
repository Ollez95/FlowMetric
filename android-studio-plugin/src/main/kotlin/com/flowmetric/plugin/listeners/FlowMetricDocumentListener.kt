package com.flowmetric.plugin.listeners

import com.flowmetric.plugin.services.FlowMetricProjectService
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile

class FlowMetricDocumentListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) {
        val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document) ?: return
        projectServices(file).forEach { it.processSave(file, document) }
    }

    override fun beforeAllDocumentsSaving() = Unit

    override fun fileContentReloaded(file: VirtualFile, document: com.intellij.openapi.editor.Document) = Unit

    override fun unsavedDocumentDropped(document: com.intellij.openapi.editor.Document) = Unit

    private fun projectServices(file: VirtualFile): List<FlowMetricProjectService> =
        ProjectManager.getInstance().openProjects
            .filter { project -> file.path.startsWith(project.basePath ?: return@filter false) }
            .map { it.service<FlowMetricProjectService>() }
}
