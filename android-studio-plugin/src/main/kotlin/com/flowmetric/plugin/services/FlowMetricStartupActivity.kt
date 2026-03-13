package com.flowmetric.plugin.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class FlowMetricStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = project.getService(FlowMetricProjectService::class.java)
        val basePath = project.basePath ?: return
        if (service.trackedRootPath() == null) {
            service.updateTrackedRoot(basePath)
        }
    }
}
