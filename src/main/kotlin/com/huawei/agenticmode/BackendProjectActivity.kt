package com.huawei.agenticmode

import com.huawei.agenticmode.vcoder.agent.GlobalBackendService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Starts the TypeScript backend when a project is opened.
 */
class BackendProjectActivity : ProjectActivity {
    companion object {
        private val LOG = Logger.getInstance(BackendProjectActivity::class.java)
    }

    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                com.huawei.agenticmode.vcoder.agent.GlobalBackendService.getInstance().getAgent(project)
                LOG.info("Backend started at project startup")
            } catch (e: Exception) {
                LOG.warn("Failed to start backend at project startup: ${e.message}", e)
            }
        }
    }
}