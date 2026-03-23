package com.example.solo.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.example.solo.SoloModeManager
import com.example.solo.SoloModePanel
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * 公共的「显示/隐藏项目面板」按钮，用于 EmptyEditorHeaderService、EditorTabsToolbarService 与 SoloModePanel。
 * 通过 [displayMode] 控制可见时机：COLLAPSED=project 折叠时显示，EXPANDED=project 展开时显示。
 */
class ToggleProjectPanelAction(
    private val project: Project,
    private val displayMode: DisplayMode = DisplayMode.COLLAPSED
) : AnAction(), DumbAware {

    private val splitIcon: Icon = IconLoader.getIcon("/icon/toggle_right.svg", javaClass).scaledTo(16, 16)

    enum class DisplayMode(val visibleWhenCollapsed: Boolean) {
        /** project 折叠时显示 */
        COLLAPSED(true),

        /** project 展开时显示 */
        EXPANDED(false)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val collapsed = SoloModePanel.isProjectCollapsed(project)
        e.presentation.isEnabledAndVisible = (collapsed == displayMode.visibleWhenCollapsed)
        e.presentation.icon = splitIcon
        e.presentation.text = if (collapsed) "Show Project" else "Hide Project"
        e.presentation.description = if (collapsed) "Show Project Panel" else "Hide Project Panel"
    }

    override fun actionPerformed(e: AnActionEvent) {
        SoloModeManager.getInstance(project).toggleProjectPanel()
    }
}
