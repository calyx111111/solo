package com.example.solo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Solo 模式下、ToggleSoloModeAction 后面的 toolbar 按钮。
 * 未进入 solo 模式时隐藏，进入后显示。
 */
class ToggleSessionAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null && SoloModeManager.getInstance(project).isSoloModeActive
        e.presentation.icon = AllIcons.Actions.SplitVertically
        e.presentation.text = "Toggle Session"
        e.presentation.description = "Toggle session (placeholder)"
    }

    override fun actionPerformed(e: AnActionEvent) {
        // TODO: 具体执行的命令内容预留
    }

    companion object {
        const val ACTION_ID = "com.example.solo.ToggleSessionAction"
    }
}
