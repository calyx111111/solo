package com.example.solo

import com.example.solo.vcoder.agent.AgentProcessManager
import com.example.solo.vcoder.webview.WebViewPanel
import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.impl.ProjectViewPane
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class SoloModePanel(
    private val project: Project,
    private val editorsSplitters: Component?,
    private val agentManager: AgentProcessManager?
) : JPanel(BorderLayout()) {

    companion object {
        private val webViewCache = mutableMapOf<Project, WebViewPanel>()
        private val projectViewPaneCache = mutableMapOf<Project, ProjectViewPane>()
        private val projectViewContentCache = mutableMapOf<Project, JComponent>()
        private val projectHeaderCache = mutableMapOf<Project, JComponent>()
        private val isProjectCollapsedMap = mutableMapOf<Project, Boolean>()
        private val isEditorPanelCollapsedMap = mutableMapOf<Project, Boolean>()

        private fun getOrCreateWebView(project: Project, agentManager: AgentProcessManager): WebViewPanel =
            webViewCache.getOrPut(project) { WebViewPanel(project, null, agentManager) }

        private fun getOrCreateProjectViewPane(project: Project): ProjectViewPane {
            return projectViewPaneCache.getOrPut(project) {
                ProjectViewPane(project).also { pane ->
                    Disposer.register(project, pane)
                }
            }
        }

        fun isProjectCollapsed(project: Project): Boolean =
            isProjectCollapsedMap.getOrDefault(project, false)

        fun setProjectCollapsed(project: Project, collapsed: Boolean) {
            isProjectCollapsedMap[project] = collapsed
        }

        fun isEditorPanelCollapsed(project: Project): Boolean =
            isEditorPanelCollapsedMap.getOrDefault(project, false)

        fun setEditorPanelCollapsed(project: Project, collapsed: Boolean) {
            isEditorPanelCollapsedMap[project] = collapsed
        }
    }

    private val splitter: JBSplitter
    private val webViewPanel: WebViewPanel
    private val rightSplitter: JBSplitter
    private val editorPanel: JPanel
    private val projectPanel: JPanel

    private var originalEditorParent: Container? = null
    private var isEditorMoved = false

    private val projectViewPane = getOrCreateProjectViewPane(project)

    private var savedRightSplitterProportionBeforeCollapse: Float = 0.3f
    private var savedSplitterProportionBeforeEditorCollapse: Float = 0.7f

    private var rightClickBlockerInstalled = false
    private val globalRightClickBlocker = AWTEventListener { event ->
        val mouseEvent = event as? MouseEvent ?: return@AWTEventListener

        when (mouseEvent.id) {
            MouseEvent.MOUSE_PRESSED,
            MouseEvent.MOUSE_RELEASED,
            MouseEvent.MOUSE_CLICKED -> blockRightClick(mouseEvent)
        }
    }

    init {
        webViewPanel = getOrCreateWebView(project, agentManager!!)
        editorPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty()
        }

        projectPanel = createProjectPanel()

        val state = SoloModeState.getInstance(project)
        rightSplitter = JBSplitter(false, state.rightSplitterProportion).apply {
            firstComponent = editorPanel
            secondComponent = projectPanel
            dividerWidth = 3
            setHonorComponentsMinimumSize(true)
        }


        // Wrap WebViewPanel with header (Exit Solo Mode + Reload buttons)
        val headerPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT)).apply {
            add(javax.swing.JButton("Reload").apply {
                toolTipText = "重新加载页面（若出现 AI未初始化 可尝试）"
                addActionListener { webViewPanel.reloadPage() }
            })
        }
//        val leftPanel = JPanel(BorderLayout()).apply {
//            add(headerPanel, BorderLayout.NORTH)
//            add(webViewPanel.component, BorderLayout.CENTER)
//        }

        var leftPanel = webViewPanel.component

        val adjustedProportion = 1.0f - state.splitterProportion
        splitter = JBSplitter(false, adjustedProportion).apply {
            firstComponent = leftPanel
            secondComponent = rightSplitter
            dividerWidth = 3
            setHonorComponentsMinimumSize(true)
        }

        if (isEditorPanelCollapsed(project)) {
            savedSplitterProportionBeforeEditorCollapse = 1.0f - state.splitterProportion
            rightSplitter.isVisible = false
            splitter.proportion = 0.98f
        }

        add(splitter, BorderLayout.CENTER)

        setupEditor()
        installGlobalRightClickBlocker()
    }


    private fun getEditorBackgroundColor(): java.awt.Color =
        EditorColorsManager.getInstance().globalScheme.defaultBackground

    /** 从 IDE 主题获取 Separator.separatorColor，lazy 确保主题切换时自动更新 */
    private fun getSeparatorLineColor(): java.awt.Color =
        JBColor.lazy { UIManager.getColor("Separator.separatorColor")!! }

    private fun applyEditorBackgroundRecursively(component: Component, color: java.awt.Color) {
        component.background = color
        if (component is JComponent) {
            component.isOpaque = true
        }
        if (component is Container) {
            for (i in 0 until component.componentCount) {
                applyEditorBackgroundRecursively(component.getComponent(i), color)
            }
        }
    }

    private fun createProjectPanel(): JPanel {
        val editorBg = getEditorBackgroundColor()

        val content = projectViewContentCache.getOrPut(project) {
            val c = projectViewPane.createComponent()
            installProjectTreeOpenBehaviorOnce(project, projectViewPane)
            c
        }

        val header = projectHeaderCache.getOrPut(project) { createHeaderInternal(editorBg) }

        applyEditorBackgroundRecursively(content, editorBg)
        content.border = JBUI.Borders.empty()

        // Header 已在 createHeaderInternal 中设置了 panel/title/toolbarComponent 的背景
        header.background = editorBg
        header.isOpaque = true
        (header as? Container)?.components?.forEach { c ->
            c.background = editorBg
            if (c is JComponent) c.isOpaque = true
            // toolbar 的 component 也需要单独设置
            if (c is Container) c.components.forEach { cc ->
                cc.background = editorBg
                if (cc is JComponent) cc.isOpaque = true
            }
        }

        val panel = SimpleToolWindowPanel(true).apply {
            background = editorBg
            isOpaque = true
        }
        panel.setContent(content)
        panel.setToolbar(header)

        panel.minimumSize = Dimension(JBUI.scale(28), 0)
        panel.isVisible = !isProjectCollapsed(project)
        return panel
    }


    private fun createHeaderInternal(editorBg: java.awt.Color): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            background = editorBg
            isOpaque = true
        }

        // empty(0, 0): 无内边距使分割线延伸到边缘
        panel.border = JBUI.Borders.merge(
            JBUI.Borders.empty(0, 0),
            JBUI.Borders.customLine(getSeparatorLineColor(), 0, 0, 1, 0),
            false
        )
        panel.preferredSize = JBUI.size(-1, 42)

        val title = JBLabel("Project").apply {
            background = editorBg
            isOpaque = true
        }
        title.font = JBFont.label().deriveFont(java.awt.Font.BOLD)
        // title的内边距
        title.border = JBUI.Borders.emptyLeft(8)

        val actionManager = ActionManager.getInstance()
        val commonActionsManager = CommonActionsManager.getInstance()

        // 直接拿 ProjectViewPane 自己创建出来的树
        val tree = projectViewPane.tree

        val treeExpander = object : DefaultTreeExpander({ tree }) {
            override fun isExpandAllVisible(): Boolean = true
            override fun isCollapseAllVisible(): Boolean = true
            override fun canExpand(): Boolean = tree != null && tree.rowCount > 0
            override fun canCollapse(): Boolean = tree != null && tree.rowCount > 0
        }

        val expandAllAction = commonActionsManager.createExpandAllAction(treeExpander, tree ?: panel)
        expandAllAction.templatePresentation.icon = AllIcons.Actions.Expandall

        val collapseAllAction = commonActionsManager.createCollapseAllAction(treeExpander, tree ?: panel)
        collapseAllAction.templatePresentation.icon = AllIcons.Actions.Collapseall

        val group = DefaultActionGroup().apply {
            add(expandAllAction)
            add(collapseAllAction)
            add(HideProjectPanelAction(project))
        }

        val toolbar = actionManager.createActionToolbar(
            "ProjectLikeHeaderToolbar",
            group,
            true
        )

        toolbar.targetComponent = tree ?: panel
        toolbar.setReservePlaceAutoPopupIcon(false)

        val toolbarComponent = toolbar.component.apply {
            background = editorBg
            isOpaque = true
        }
        toolbarComponent.border = JBUI.Borders.empty()

        panel.add(title, BorderLayout.WEST)
        panel.add(toolbarComponent, BorderLayout.EAST)

        return panel
    }

    fun toggleProjectPanel() {
        if (isProjectCollapsed(project)) {
            rightSplitter.proportion = savedRightSplitterProportionBeforeCollapse
            setProjectCollapsed(project, false)
            projectPanel.isVisible = true
        } else {
            savedRightSplitterProportionBeforeCollapse = rightSplitter.proportion
            rightSplitter.proportion = 0.98f
            setProjectCollapsed(project, true)
            projectPanel.isVisible = false
        }
        revalidate()
        repaint()
    }

    /** 隐藏/显示主 splitter 的右侧（secondComponent = rightSplitter，含 Editor + Project） */
    fun toggleEditorPanel() {
        if (isEditorPanelCollapsed(project)) {
            splitter.proportion = savedSplitterProportionBeforeEditorCollapse
            setEditorPanelCollapsed(project, false)
            rightSplitter.isVisible = true
        } else {
            savedSplitterProportionBeforeEditorCollapse = splitter.proportion
            splitter.proportion = 0.98f
            setEditorPanelCollapsed(project, true)
            rightSplitter.isVisible = false
        }
        revalidate()
        repaint()
    }

    private class HideProjectPanelAction(private val project: Project) : AnAction() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            SoloModeManager.getInstance(project).toggleProjectPanel()
        }

        override fun update(e: AnActionEvent) {
            val collapsed = isProjectCollapsed(project)
            e.presentation.icon = AllIcons.Actions.SplitVertically
            e.presentation.text = if (collapsed) "Show Project" else "Hide Project"
        }
    }

    private fun setupEditor() {
        if (editorsSplitters != null) {
            originalEditorParent = editorsSplitters.parent

            if (originalEditorParent != null) {
                originalEditorParent!!.remove(editorsSplitters)
                isEditorMoved = true
            }

            editorPanel.add(editorsSplitters, BorderLayout.CENTER)

            println("SoloModePanel: EditorsSplitters moved to solo panel")
        } else {
            showEmptyEditorMessage()
        }
    }

    private fun showEmptyEditorMessage() {
        editorPanel.removeAll()
        val emptyLabel = JLabel("No file open - Press Ctrl+Shift+N to open a file").apply {
            horizontalAlignment = SwingConstants.CENTER
        }
        editorPanel.add(emptyLabel, BorderLayout.CENTER)
        editorPanel.revalidate()
        editorPanel.repaint()
    }

    private fun installGlobalRightClickBlocker() {
        if (rightClickBlockerInstalled) return

        Toolkit.getDefaultToolkit().addAWTEventListener(
            globalRightClickBlocker,
            AWTEvent.MOUSE_EVENT_MASK
        )
        rightClickBlockerInstalled = true
    }


    private fun uninstallGlobalRightClickBlocker() {
        if (!rightClickBlockerInstalled) return

        Toolkit.getDefaultToolkit().removeAWTEventListener(globalRightClickBlocker)
        rightClickBlockerInstalled = false
    }

    private fun blockRightClick(e: MouseEvent) {
        if (!(SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger)) return
        if (!isEventFromThisPanel(e)) return
        if (!SwingUtilities.isDescendingFrom(e.source as? Component ?: return, projectPanel)) return

        e.consume()
    }

    private fun isEventFromThisPanel(e: MouseEvent): Boolean {
        val source = e.source as? Component ?: return false
        return source === this || SwingUtilities.isDescendingFrom(source, this)
    }

    fun restoreEditorComponent() {
        if (editorsSplitters != null && isEditorMoved && originalEditorParent != null) {
            editorPanel.remove(editorsSplitters)
            originalEditorParent!!.add(editorsSplitters, BorderLayout.CENTER)

            originalEditorParent!!.revalidate()
            originalEditorParent!!.repaint()

            println("SoloModePanel: EditorsSplitters restored to original parent")
        }

        isEditorMoved = false
    }

    fun saveSplitterProportion() {
        val state = SoloModeState.getInstance(project)
        state.splitterProportion = 1.0f - splitter.proportion
        if (!isProjectCollapsed(project)) {
            state.rightSplitterProportion = rightSplitter.proportion
        }
    }

    override fun removeNotify() {
        uninstallGlobalRightClickBlocker()

        super.removeNotify()
        try {
            saveSplitterProportion()
        } catch (_: Exception) {
            // IDE 关闭时 SoloModeState 可能已不可用，忽略
        }
        disposeWebView()
    }

    fun disposeWebView() {
    }

    fun getWebViewPanel(): WebViewPanel? = webViewPanel
}


private fun installProjectTreeOpenBehaviorOnce(project: Project, pane: ProjectViewPane) {
    val tree = pane.tree ?: return

    val installedKey = "solo.project.tree.open.behavior.installed"
    if ((tree.getClientProperty(installedKey) as? Boolean) == true) return
    tree.putClientProperty(installedKey, true)

    tree.addMouseListener(object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                openNodeAtEvent(project, pane, e)
                e.consume()
            }
        }
    })
}

private fun openNodeAtEvent(project: Project, pane: ProjectViewPane, e: MouseEvent) {
    if (project.isDisposed) return

    val tree = pane.tree ?: return
    val path = tree.getPathForLocation(e.x, e.y) ?: return
    tree.selectionPath = path

    if (tryNavigate(project, path)) return

    val file = extractVirtualFile(path) ?: return
    if (!file.isValid || file.isDirectory) return

    FileEditorManager.getInstance(project).openFile(file, true)
}

private fun tryNavigate(project: Project, path: TreePath): Boolean {
    val node = path.lastPathComponent ?: return false

    fun navigateFrom(any: Any?): Boolean {
        when (any) {
            is Navigatable -> {
                if (any.canNavigate()) {
                    any.navigate(true)
                    return true
                }
            }

            is ProjectViewNode<*> -> {
                val value = any.value
                if (value is Navigatable && value.canNavigate()) {
                    value.navigate(true)
                    return true
                }

                val vf = any.virtualFile
                if (vf != null && vf.isValid && !vf.isDirectory) {
                    OpenFileDescriptor(project, vf).navigate(true)
                    return true
                }
            }

            is VirtualFile -> {
                if (any.isValid && !any.isDirectory) {
                    OpenFileDescriptor(project, any).navigate(true)
                    return true
                }
            }
        }
        return false
    }

    if (navigateFrom(node)) return true

    if (node is DefaultMutableTreeNode) {
        if (navigateFrom(node.userObject)) return true
    }

    return false
}

private fun extractVirtualFile(path: TreePath): VirtualFile? {
    val node = path.lastPathComponent ?: return null

    when (node) {
        is ProjectViewNode<*> -> {
            node.virtualFile?.let { return it }
            val value = node.value
            if (value is VirtualFile) return value
        }

        is DefaultMutableTreeNode -> {
            val userObject = node.userObject
            if (userObject is ProjectViewNode<*>) {
                userObject.virtualFile?.let { return it }
                val value = userObject.value
                if (value is VirtualFile) return value
            }
            if (userObject is VirtualFile) return userObject
        }
    }

    return null
}
