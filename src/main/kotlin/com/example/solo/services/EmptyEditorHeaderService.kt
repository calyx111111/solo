package com.example.solo.services

import com.example.solo.actions.ToggleProjectPanelAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * 空 Editor 区域 Header 管理服务。
 * - 项目打开时在 SoloModeStartupActivity 初始化，通过 FileEditorChangeDispatcher 监听 tab 页变化
 * - 默认只监听，不处理（enabled=false）
 * - 进入 Solo 模式时 enable()，根据 tab 数量动态添加/移除 header
 * - 退出 Solo 模式时 disable()，移除 header，但监听继续保留
 */
@Service(Service.Level.PROJECT)
class EmptyEditorHeaderService(
    private val project: Project
) : Disposable {

    private val enabled = AtomicBoolean(false)
    private var editorsSplittersProvider: (() -> Component?)? = null
    private var tabChangeCallback: (() -> Unit)? = null

    /** 配置 editorsSplitters 提供者（与 EditorTabsToolbarService 一致），通过 parent 获取 editorPanel，支持可重入 */
    fun install(editorsSplittersProvider: () -> Component?) {
        val wasInstalled = this.editorsSplittersProvider != null
        this.editorsSplittersProvider = editorsSplittersProvider
        if (wasInstalled && enabled.get()) {
            refreshNow()
        }
    }

    init {
        val callback: () -> Unit = { onTabChanged() }
        tabChangeCallback = callback
        project.service<FileEditorChangeDispatcher>().addCallback(callback)
    }

    private fun onTabChanged() {
        if (!enabled.get()) return
        SwingUtilities.invokeLater { updateHeaderVisibility() }
    }

    /** 开启 header 功能：根据 tab 数量动态管理 header（使用 install 配置的 provider） */
    fun enable() {
        if (!enabled.compareAndSet(false, true)) return
        refreshNow()
    }

    /** 关闭 header 功能：移除 header（如有），监听继续保留 */
    fun disable() {
        if (!enabled.compareAndSet(true, false)) return
        removeHeaderIfPresent()
    }

    private fun refreshNow() {
        SwingUtilities.invokeLater { updateHeaderVisibility() }
    }

    fun isEnabled(): Boolean = enabled.get()

    private fun getEditorPanel(): JPanel? =
        editorsSplittersProvider?.invoke()?.parent as? JPanel

    private fun updateHeaderVisibility() {
        val panel = getEditorPanel() ?: return
        val hasOpenFiles = FileEditorManager.getInstance(project).openFiles.isNotEmpty()
        val layout = panel.layout
        val header = if (layout is BorderLayout) layout.getLayoutComponent(panel, BorderLayout.NORTH) else null

        when {
            hasOpenFiles && header != null -> {
                panel.remove(header)
                panel.revalidate()
                panel.repaint()
            }
            !hasOpenFiles && header == null -> {
                val emptyHeader = createEmptyEditorHeader(panel)
                panel.add(emptyHeader, BorderLayout.NORTH)
                panel.revalidate()
                panel.repaint()
            }
        }
    }

    private fun removeHeaderIfPresent() {
        val panel = getEditorPanel() ?: return
        val layout = panel.layout
        val header = if (layout is BorderLayout) layout.getLayoutComponent(panel, BorderLayout.NORTH) else null
        if (header != null) {
            panel.remove(header)
            panel.revalidate()
            panel.repaint()
        }
    }

    private fun createEmptyEditorHeader(targetComponent: JComponent): JComponent {
        val editorBg = EditorColorsManager.getInstance().globalScheme.defaultBackground
        val separatorColor = JBColor.lazy { UIManager.getColor("Separator.separatorColor")!! }

        val panel = JPanel(BorderLayout()).apply {
            background = editorBg
            isOpaque = true
        }
        panel.border = JBUI.Borders.merge(
            JBUI.Borders.empty(0, 0),
            JBUI.Borders.customLine(separatorColor, 0, 0, 1, 0),
            false
        )
        panel.preferredSize = JBUI.size(-1, 42)

        val actionGroup = DefaultActionGroup().apply {
            add(ToggleProjectPanelAction(project, ToggleProjectPanelAction.DisplayMode.COLLAPSED))
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "SoloEmptyEditorHeaderToolbar",
            actionGroup,
            true
        )
        toolbar.setReservePlaceAutoPopupIcon(false)
        toolbar.targetComponent = targetComponent

        val toolbarComponent = toolbar.component.apply {
            background = editorBg
            isOpaque = true
        }
        toolbarComponent.border = JBUI.Borders.emptyRight(8)
        panel.add(toolbarComponent, BorderLayout.EAST)
        return panel
    }

    override fun dispose() {
        tabChangeCallback?.let { project.service<FileEditorChangeDispatcher>().removeCallback(it) }
        tabChangeCallback = null
        editorsSplittersProvider = null
        enabled.set(false)
    }
}
