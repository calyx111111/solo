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
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * 空 Editor 区域 Header 管理服务。
 * - 项目打开时在 SoloModeStartupActivity 初始化，通过 FileEditorChangeDispatcher 监听 tab 页变化
 * - 默认只监听，不处理（enabled=false）
 * - 进入 Solo 模式时 enable()，根据 tab 数量动态添加/移除 header
 * - 无 tab 时：添加 header + 在 CENTER 上覆盖一层背景（不破坏原 editor 结构）
 * - 退出 Solo 模式时 disable()，移除 header 并恢复中间区域，但监听继续保留
 */
@Service(Service.Level.PROJECT)
class EmptyEditorHeaderService(
    private val project: Project
) : Disposable {

    private val enabled = AtomicBoolean(false)
    private var editorsSplittersProvider: (() -> Component?)? = null
    private var tabChangeCallback: (() -> Unit)? = null

    /** overlay 层（header+gray），与 editor 同级，JLayeredPane 中在上层显示 */
    private var overlayPanel: JPanel? = null

    /** JLayeredPane 容器，DEFAULT 层=editor，PALETTE 层=overlay */
    private var layeredContainer: JLayeredPane? = null

    private val OVERLAY_KEY = "solo.editor.empty.overlay"

    init {
        val callback: () -> Unit = { onTabChanged() }
        tabChangeCallback = callback
        project.service<FileEditorChangeDispatcher>().addCallback(callback)
    }

    /** 配置 editorsSplitters 提供者（与 EditorTabsToolbarService 一致），通过 parent 获取 editorPanel，支持可重入 */
    fun install(editorsSplittersProvider: () -> Component?) {
        val wasInstalled = this.editorsSplittersProvider != null
        this.editorsSplittersProvider = editorsSplittersProvider
        if (wasInstalled && enabled.get()) {
            refreshNow()
        }
    }

    private fun onTabChanged() {
        if (!enabled.get()) return
        refreshNow()
    }

    /** 开启 header 功能：根据 tab 数量动态管理 header（使用 install 配置的 provider） */
    fun enable() {
        if (!enabled.compareAndSet(false, true)) return
        refreshNow()
    }

    /** 关闭 header 功能：移除 header（如有），恢复中间区域，监听继续保留 */
    fun disable() {
        if (!enabled.compareAndSet(true, false)) return
        removeHeaderAndRestoreCenter()
    }

    private fun refreshNow() {
        SwingUtilities.invokeLater { updateHeaderVisibility() }
    }

    fun isEnabled(): Boolean = enabled.get()

    /** 向上查找带 BorderLayout 的 JPanel（真正的 editor 区域容器） */
    private fun getEditorPanel(): JPanel? {
        var p = editorsSplittersProvider?.invoke()?.parent
        while (p != null) {
            if (p is JPanel && p.layout is BorderLayout) return p
            p = p.parent
        }
        return null
    }

    private fun updateHeaderVisibility() {
        val panel = getEditorPanel() ?: return
        val borderLayout = panel.layout as? BorderLayout ?: return
        val hasOpenFiles = FileEditorManager.getInstance(project).openFiles.isNotEmpty()
        val centerComponent = borderLayout.getLayoutComponent(panel, BorderLayout.CENTER)

        when {
            shouldHideOverlay(hasOpenFiles) -> {
                hideOverlay(panel)
            }

            shouldInstallOverlay(hasOpenFiles, centerComponent) -> {
                installOverlay(panel, centerComponent)
            }

            shouldShowOverlay(hasOpenFiles, centerComponent) -> {
                showOverlay(panel)
            }
        }
    }

    private fun shouldHideOverlay(hasOpenFiles: Boolean): Boolean {
        return hasOpenFiles && overlayPanel != null
    }

    private fun shouldInstallOverlay(hasOpenFiles: Boolean, centerComponent: Component?): Boolean {
        return !hasOpenFiles &&
                (layeredContainer == null || !isOverlayComponent(centerComponent))
    }

    private fun shouldShowOverlay(hasOpenFiles: Boolean, centerComponent: Component?): Boolean {
        return !hasOpenFiles &&
                centerComponent != null &&
                isOverlayComponent(centerComponent)
    }

    private fun isOverlayComponent(component: Component?): Boolean {
        return (component as? JComponent)?.getClientProperty(OVERLAY_KEY) == true
    }

    private fun hideOverlay(panel: JPanel) {
        overlayPanel?.isVisible = false
        refreshPanel(panel)
    }

    private fun showOverlay(panel: JPanel) {
        overlayPanel?.isVisible = true
        refreshPanel(panel)
    }

    private fun installOverlay(panel: JPanel, centerComponent: Component?) {
        if (centerComponent != null) {
            val overlay = createOverlayPanel(panel)
            val layered = createLayeredContainer(centerComponent, overlay)

            panel.add(layered, BorderLayout.CENTER)
            layeredContainer = layered
            overlayPanel = overlay
        }
        refreshPanel(panel)
    }

    private fun createLayeredContainer(centerComponent: Component, overlay: JPanel): JLayeredPane {
        val layered = JLayeredPane().apply {
            putClientProperty(OVERLAY_KEY, true)
        }

        layered.layout = createOverlayLayout(centerComponent)
        layered.add(centerComponent, JLayeredPane.DEFAULT_LAYER, 0)
        layered.add(overlay, JLayeredPane.PALETTE_LAYER, 0)

        return layered
    }

    private fun createOverlayLayout(centerComponent: Component): LayoutManager {
        return object : LayoutManager {
            override fun addLayoutComponent(name: String, comp: Component) {}

            override fun removeLayoutComponent(comp: Component) {}

            override fun preferredLayoutSize(parent: Container) = centerComponent.preferredSize

            override fun minimumLayoutSize(parent: Container) = preferredLayoutSize(parent)

            override fun layoutContainer(parent: Container) {
                val w = parent.width.coerceAtLeast(0)
                val h = parent.height.coerceAtLeast(0)
                parent.components.forEach { it.setBounds(0, 0, w, h) }
            }
        }
    }

    private fun createOverlayPanel(targetComponent: JComponent): JPanel {
        val header = createEmptyEditorHeader(targetComponent)
        val grayPanel = createGrayFillPanel()
        val editorBg = EditorColorsManager.getInstance().globalScheme.defaultBackground

        return JPanel(BorderLayout()).apply {
            background = editorBg
            isOpaque = true
            add(header, BorderLayout.NORTH)
            add(grayPanel, BorderLayout.CENTER)
        }
    }

    private fun removeHeaderAndRestoreCenter() {
        val panel = getEditorPanel() ?: return

        layeredContainer?.let { layered ->
            val editorsSplitters = layered.getComponent(0)
            panel.remove(layered)
            panel.add(editorsSplitters, BorderLayout.CENTER)
            layeredContainer = null
            overlayPanel = null
        }

        refreshPanel(panel)
    }

    private fun refreshPanel(panel: JPanel) {
        panel.revalidate()
        panel.repaint()
    }

    /** 创建背景填充面板，使用 icon_with_background.svg 适配尺寸填充整个区域 */
    private fun createGrayFillPanel(): JComponent {
        val bgIcon = IconLoader.getIcon("/icon/icon_with_background.svg", javaClass)
        return object : JPanel() {
            init {
                isOpaque = false
            }

            override fun paintComponent(g: Graphics) {
                if (width <= 0 || height <= 0) return

                val iw = bgIcon.iconWidth.coerceAtLeast(1)
                val ih = bgIcon.iconHeight.coerceAtLeast(1)
                val g2 = g.create() as Graphics2D
                try {
                    g2.scale(width.toDouble() / iw, height.toDouble() / ih)
                    bgIcon.paintIcon(this, g2, 0, 0)
                } finally {
                    g2.dispose()
                }
            }
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
        tabChangeCallback?.let {
            project.service<FileEditorChangeDispatcher>().removeCallback(it)
        }
        tabChangeCallback = null
        editorsSplittersProvider = null
        layeredContainer = null
        overlayPanel = null
        enabled.set(false)
    }
}