package com.example.solo

import com.example.solo.vcoder.agent.GlobalBackendService
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
        // 放到后台线程，避免阻塞项目启动流程
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                GlobalBackendService.getInstance().agent
                LOG.info("Backend started at project startup")
            } catch (e: Exception) {
                LOG.warn("Failed to start backend at project startup: ${e.message}", e)
            }
        }
    }
}