package com.huawei.agenticmode

import com.huawei.agenticmode.login.LoginManager
import com.huawei.agenticmode.services.EditorTabsRepairService
import com.huawei.agenticmode.services.EmptyEditorHeaderService
import com.huawei.agenticmode.services.JsCrashMonitorService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 在项目打开后恢复 Solo Mode 状态。
 */
class SoloModeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<EditorTabsRepairService>()
        project.service<EmptyEditorHeaderService>()
        project.service<JsCrashMonitorService>().start()

        val state = SoloModeState.getInstance(project)
        if (!state.isSoloModeEnabled) return

        // Frame/UI 可能尚未完全就绪，延后到合适时机恢复
        ToolWindowManager.getInstance(project).invokeLater {
            if (!project.isDisposed && SoloModeState.getInstance(project).isSoloModeEnabled) {
                if (!LoginManager.getInstance().hasLogin()) {
                    SoloModeManager.getInstance(project).enterSoloMode()
                }
            }
        }
    }
}