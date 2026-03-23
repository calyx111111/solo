package com.example.solo.actions

import com.example.solo.SoloModeManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Solo 模式下、ToggleSoloModeAction 后面的 toolbar 按钮。
 * 未进入 solo 模式时隐藏，进入后显示。
 */
class ToggleSessionAction : AnAction() {

    private val splitIcon: Icon = IconLoader.getIcon("/icon/toggle_left.svg", javaClass)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null && SoloModeManager.getInstance(project).isSoloModeActive
        e.presentation.icon = splitIcon
        e.presentation.text = "Toggle Session"
        e.presentation.description = "Toggle session (placeholder)"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SoloModeManager.getInstance(project).emitEventToWebView(
            "toggle-session-panel",
            "{}"
        )
    }

    companion object {
        const val ACTION_ID = "com.example.solo.ToggleSessionAction"
    }
}
