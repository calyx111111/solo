package com.huawei.agenticmode

import com.huawei.agenticmode.actions.SoloModeToggleButton
import com.huawei.agenticmode.actions.ToggleEditorButton
import com.huawei.agenticmode.actions.ToggleSessionAction
import com.huawei.agenticmode.services.EditorTabDragLockService
import com.huawei.agenticmode.services.EditorTabPopupService
import com.huawei.agenticmode.services.EditorTabsRepairService
import com.huawei.agenticmode.services.EditorTabsToolbarService
import com.huawei.agenticmode.services.EditorPopupService
import com.huawei.agenticmode.services.EmptyEditorHeaderService
import com.huawei.agenticmode.services.ShortcutBlockerService
import com.huawei.agenticmode.vcoder.agent.AgentProcessManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ContainerEvent
import java.awt.event.ContainerListener
import javax.swing.*

class SoloModeManager(private val project: Project) : Disposable {

    private data class PendingWebViewEvent(
        val event: String,
        val payload: String
    )

    private var soloModePanel: SoloModePanel? = null
    private val pendingWebViewEvents = ArrayDeque<PendingWebViewEvent>()

    private val storedToolWindowStates = mutableMapOf<String, Boolean>()
    private var wasStatusBarVisible = true

    private var ideFrame: IdeFrame? = null
    private var frame: Window? = null
    /** 全屏 Solo 遮罩根面板，挂在 [soloOverlayLayeredPane] 的较高层上 */
    private var soloOverlayRoot: JPanel? = null
    private var soloOverlayLayeredPane: JLayeredPane? = null
    private var soloOverlayBoundsListener: ComponentAdapter? = null
    /** 用于在标题栏高度变化时同步 overlay，退出时移除监听 */
    private var soloOverlayHeaderForBounds: Component? = null
    private var rootPane: JRootPane? = null

    private var editorComponent: Component? = null
    private var editorParent: Container? = null
    private var editorConstraints: Any? = null

    // 只处理标题栏 header 区域中的 toolbar
    private val storedToolbars = mutableListOf<ActionToolbarImpl>()

    // 每个 toolbar 对应的子组件（按钮）列表
    private val originalToolbarComponents = mutableMapOf<ActionToolbarImpl, MutableList<Component>>()
    private val toolbarContainerListeners = mutableMapOf<ActionToolbarImpl, ContainerListener>()

    // toolbar 隐藏的按钮原始visible状态，退出的时候进行还原
    private val toolbarComponentHiddenOriginalVisible = mutableMapOf<Component, Boolean>()

    // 精确匹配到的 New UI 左上角 Main Menu 按钮
    private var storedMainMenuButton: Component? = null

    private var firstOpen = true

    /** 是否实际处于 Solo Mode（UI 已切换）。与持久化的 isSoloModeEnabled 区分：启动恢复时后者为 true 但前者为 false。 */
    val isSoloModeActive: Boolean
        get() = soloModePanel != null

    private fun setupEditorController(editorsSplitters: Component?) {
        if (editorsSplitters == null) return
        project.service<EditorTabsToolbarService>().install { editorsSplitters }
        project.service<EditorTabPopupService>().install { editorsSplitters }
        project.service<EditorTabDragLockService>().install { editorsSplitters }
        project.service<EmptyEditorHeaderService>().install { editorsSplitters }
    }

    fun enterSoloMode() {
        if (isSoloModeActive) return

        // todo: deveco首次打开时，editortab toolbar会不显示影响按钮状态 通过重新打开tab页规避刷新
        if (firstOpen.also { firstOpen = false }) {
            project.service<EditorTabsRepairService>().repair()
        }

        ApplicationManager.getApplication().invokeLater {
            doEnterSoloMode()
        }
    }

    private fun doEnterSoloMode() {
        ideFrame = WindowManager.getInstance().getIdeFrame(project)
        if (ideFrame == null) {
            println("SoloMode: Cannot find IDE frame")
            return
        }

        println("SoloMode: Entering solo mode...")

        storeUIStates()
        hideAllUIComponents()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val agentManager =
                    com.huawei.agenticmode.vcoder.agent.GlobalBackendService.getInstance().getAgentAndWaitReady(project)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        createSoloModeUI(agentManager)

                        project.service<ShortcutBlockerService>().enable()
                        project.service<EditorTabsToolbarService>().enable()
                        project.service<EditorPopupService>().enable()
                        project.service<EditorTabPopupService>().enable()
                        project.service<EditorTabDragLockService>().enable()
                        project.service<EmptyEditorHeaderService>().enable()

                        SoloModeState.getInstance(project).isSoloModeEnabled = true
                        println("SoloMode: Solo mode activated")
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    println("SoloMode: Backend failed: " + e.message)
                }
            }
        }
    }

    fun exitSoloMode() {
        if (!isSoloModeActive) return

        if (ApplicationManager.getApplication().isDispatchThread) {
            doExitSoloMode()
        } else {
            ApplicationManager.getApplication().invokeLater {
                doExitSoloMode()
            }
        }
    }

    private fun doExitSoloMode() {
        println("SoloMode: Exiting solo mode...")

        project.service<ShortcutBlockerService>().disable()
        project.service<EditorTabsToolbarService>().disable()
        project.service<EditorPopupService>().disable()
        project.service<EditorTabPopupService>().disable()
        project.service<EditorTabDragLockService>().disable()
        project.service<EmptyEditorHeaderService>().disable()

        try {
            if (!project.isDisposed) {
                removeSoloModeUI()
                restoreUIComponents()
            }
        } catch (e: Exception) {
            println("SoloMode: Error during exit cleanup: ${e.message}")
        } finally {
            try {
                SoloModeState.getInstance(project).isSoloModeEnabled = false
            } catch (_: Exception) {
                println("SoloMode: Could not update state (IDE may be shutting down)")
            }
        }

        println("SoloMode: Solo mode deactivated")
    }

    fun toggleSoloMode() {
        if (isSoloModeActive) {
            exitSoloMode()
        } else {
            enterSoloMode()
        }
    }

    fun toggleProjectPanel() {
        soloModePanel?.toggleProjectPanel()
    }

    fun toggleEditorPanel() {
        soloModePanel?.toggleEditorPanel()
    }

    fun emitEventToWebView(event: String, payload: String): Boolean {
        val webViewPanel = soloModePanel?.getWebViewPanel()
        if (webViewPanel == null) {
            println("SoloMode: queue webview event -> $event")
            pendingWebViewEvents.addLast(PendingWebViewEvent(event, payload))
            while (pendingWebViewEvents.size > 20) {
                pendingWebViewEvents.removeFirst()
            }
            return true
        }
        println("SoloMode: emit webview event -> $event")
        webViewPanel.emitEvent(event, payload)
        return true
    }

    private fun flushPendingWebViewEvents() {
        val webViewPanel = soloModePanel?.getWebViewPanel() ?: return
        if (pendingWebViewEvents.isNotEmpty()) {
            println("SoloMode: flush pending webview events -> ${pendingWebViewEvents.size}")
        }
        while (pendingWebViewEvents.isNotEmpty()) {
            val pendingEvent = pendingWebViewEvents.removeFirst()
            webViewPanel.emitEvent(pendingEvent.event, pendingEvent.payload)
        }
    }

    private fun storeUIStates() {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        for (id in toolWindowManager.toolWindowIds) {
            val toolWindow = toolWindowManager.getToolWindow(id)
            if (toolWindow != null && toolWindow.isVisible) {
                storedToolWindowStates[id] = toolWindow.isVisible
            }
        }

        val statusBar = ideFrame?.statusBar
        wasStatusBarVisible = statusBar?.component?.isVisible ?: true

        storedToolbars.clear()
        originalToolbarComponents.clear()
        toolbarComponentHiddenOriginalVisible.clear()

        storeHeaderToolbarsOnly()
        storeMainMenuButtons()
    }

    /**
     * 只收集标题栏 header 区域中的 toolbar。
     * 不再扫描整个 IDE 树，避免误伤 EditorTabs / EditorMarkup / Floating toolbar。
     *  顶部有插件自己注册的toolbar，不能只用place来判断
     */
    private fun storeHeaderToolbarsOnly() {
        val ideFrameComponent = ideFrame?.component ?: return

        val headerRoot = findToolbarFrameHeader(ideFrameComponent)
        if (headerRoot == null) {
            println("SoloMode: ToolbarFrameHeader not found, skip header toolbar collection")
            return
        }

        println("SoloMode: Found header root = ${describeComponent(headerRoot)}")

        findAndStoreToolbarsUnderHeader(headerRoot)

        println("SoloMode: storeHeaderToolbarsOnly done, toolbarCount=${storedToolbars.size}")
    }

    private fun findToolbarFrameHeader(root: Container): Container? {
        if (isHeaderRoot(root)) return root

        for (component in root.components) {
            if (component is Container) {
                val found = findToolbarFrameHeader(component)
                if (found != null) return found
            }
        }
        return null
    }

    private fun isHeaderRoot(container: Container): Boolean {
        val name = container.javaClass.name
        return name.contains("ToolbarFrameHeader")
    }

    private fun findAndStoreToolbarsUnderHeader(container: Container) {
        for (component in container.components) {
            if (component is ActionToolbarImpl) {
                storedToolbars.add(component)

                val children = mutableListOf<Component>()
                for (i in 0 until component.componentCount) {
                    children.add(component.getComponent(i))
                }
                originalToolbarComponents[component] = children

                println("SoloMode: Stored header toolbar = ${describeComponent(component)}")
            }

            if (component is Container) {
                findAndStoreToolbarsUnderHeader(component)
            }
        }
    }

    private fun storeMainMenuButtons() {
        storedMainMenuButton = null
        val ideFrameComponent = ideFrame?.component ?: return

        val headerRoot = findToolbarFrameHeader(ideFrameComponent)
        if (headerRoot == null) {
            println("SoloMode: Header root not found, skip main menu button search")
            return
        }

        storedMainMenuButton = findMainMenuButtonExactly(headerRoot)
        println("SoloMode: storedMainMenuButton = ${describeComponent(storedMainMenuButton)}")
    }

    private fun findMainMenuButtonExactly(container: Container): Component? {
        for (component in container.components) {
            if (isExactMainMenuButton(component)) return component
            if (component is Container) {
                findMainMenuButtonExactly(component)?.let { return it }
            }
        }
        return null
    }

    private fun isExactMainMenuButton(component: Component): Boolean {
        val className = component.javaClass.name
        return (
                className == MAIN_MENU_BUTTON_EXACT_CLASS ||
                        className.contains(MAIN_MENU_BUTTON_CLASS_SUBSTRING)
                )
    }

    private fun hideAllUIComponents() {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        for ((id, wasVisible) in storedToolWindowStates) {
            val toolWindow = toolWindowManager.getToolWindow(id)
            if (toolWindow != null && wasVisible) {
                toolWindow.hide()
            }
        }

        val statusBar = ideFrame?.statusBar
        statusBar?.component?.isVisible = false

        hideHeaderToolbarButtons()
        hideMainMenuButtons()
    }

    private fun hideHeaderToolbarButtons() {
        for (toolbar in storedToolbars) {
            applyToolbarHide(toolbar)
            installToolbarNewComponentBlocker(toolbar)
        }
    }

    /**
     * 通过 visible 隐藏 header toolbar 里的非 Solo 按钮。
     * 因为 storedToolbars 已经只来自标题栏 header，所以不会再误伤 editor tabs 的按钮。
     */
    private fun applyToolbarHide(toolbar: ActionToolbarImpl) {
        val components = originalToolbarComponents[toolbar] ?: return

        for (component in components) {
            if (!isSoloModeToggleButton(component)) {
                toolbarComponentHiddenOriginalVisible[component] = component.isVisible
                component.isVisible = false
            }
        }

        toolbar.revalidate()
        toolbar.repaint()
    }

    /** 监听 toolbar 新增组件，非 Solo Mode 切换按钮则隐藏，避免启动恢复时晚注册的按钮显示出来 */
    private fun installToolbarNewComponentBlocker(toolbar: ActionToolbarImpl) {
        if (toolbarContainerListeners.containsKey(toolbar)) return

        val listener = object : ContainerListener {
            override fun componentAdded(e: ContainerEvent) {
                val child = e.child
                if (!isSoloModeToggleButton(child)) {
                    // applyToolbarHide 设置 visible=false 后，layout 可能触发 remove+add，此时 child 已是 false
                    // 若已记录过则视为 re-add，不覆盖已保存的原始状态，否则会导致恢复时认为该 child 是不需要显示的
                    if (child !in toolbarComponentHiddenOriginalVisible) {
                        toolbarComponentHiddenOriginalVisible[child] = child.isVisible
                    }
                    child.isVisible = false
                }
            }

            override fun componentRemoved(e: ContainerEvent) {}
        }
        toolbar.addContainerListener(listener)
        toolbarContainerListeners[toolbar] = listener
    }

    private fun uninstallToolbarNewComponentBlockers() {
        for ((toolbar, listener) in toolbarContainerListeners) {
            try {
                toolbar.removeContainerListener(listener)
            } catch (_: Exception) {
            }
        }
        toolbarContainerListeners.clear()
    }

    private fun hideMainMenuButtons() {
        storedMainMenuButton?.let { button ->
            button.isVisible = false
            button.parent?.revalidate()
            button.parent?.repaint()
        }
    }

    private fun isSoloModeToggleButton(component: Component): Boolean {


        if (component is ToggleEditorButton) {
            return true
        }

        if (component is SoloModeToggleButton) {
            return true
        }

        val actionManager = ActionManager.getInstance()

        // 普通 Action 的 toolbar 按钮（ActionButton）
        if (component is ActionButton) {
            val actionId = actionManager.getId(component.action)
            if (actionId == ToggleSessionAction.ACTION_ID) return true
        }

        if (component is Container) {
            for (child in component.components) {
                if (isSoloModeToggleButton(child)) return true
            }
        }

        return false
    }

    private fun findEditorComponent(container: Container): Component? {
        val className = container.javaClass.name

        if (className.contains("EditorComponent") ||
            className.contains("EditorImpl") ||
            className.contains("FileEditorManagerImpl") ||
            className.contains("EditorComposite") ||
            className.contains("EditorsSplitters")
        ) {
            return container
        }

        for (component in container.components) {
            val compClassName = component.javaClass.name
            if (compClassName.contains("EditorsSplitters") ||
                compClassName.contains("EditorComposite") ||
                compClassName.contains("DesktopEditorsProvider") ||
                compClassName.contains("FileEditorManagerImpl")
            ) {
                return component
            }

            if (component is Container) {
                val found = findEditorComponent(component)
                if (found != null) return found
            }
        }

        return null
    }

    private fun findEditorsSplitters(container: Container): Component? {
        for (component in container.components) {
            val className = component.javaClass.simpleName
            if (className == "EditorsSplitters" ||
                className == "EditorComposite" ||
                className == "DesktopSplitters"
            ) {
                return component
            }

            if (component is Container) {
                val found = findEditorsSplitters(component)
                if (found != null) return found
            }
        }
        return null
    }

    private fun createSoloModeUI(agentManager: com.huawei.agenticmode.vcoder.agent.AgentProcessManager) {
        val ideFrameComponent = ideFrame?.component ?: return

        frame = SwingUtilities.getWindowAncestor(ideFrameComponent)

        rootPane = SwingUtilities.getRootPane(ideFrameComponent)
        if (rootPane == null) {
            println("SoloMode: Cannot find root pane")
            return
        }

        val content = rootPane!!.contentPane

        val editorsSplitters = findEditorsSplitters(content)
        println("SoloMode: Found editorsSplitters: $editorsSplitters")

        setupEditorController(editorsSplitters)

        if (editorsSplitters != null) {
            editorComponent = editorsSplitters
            editorParent = editorsSplitters.parent

            if (editorParent != null && editorParent!!.layout is BorderLayout) {
                editorConstraints = BorderLayout.CENTER
            }
        }

        soloModePanel = SoloModePanel(
            project,
            editorsSplitters,
            agentManager
        )

        val layered = rootPane!!.layeredPane
        soloOverlayLayeredPane = layered

        val overlay = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UIManager.getColor("Panel.background")
            add(soloModePanel, BorderLayout.CENTER)
        }
        soloOverlayRoot = overlay

        val boundsListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                layoutSoloOverlay(layered, overlay)
            }
        }
        layered.addComponentListener(boundsListener)
        soloOverlayBoundsListener = boundsListener

        val frameRoot = ideFrameComponent as? Container
        if (frameRoot != null) {
            val header = findToolbarFrameHeader(frameRoot)
            if (header != null) {
                header.addComponentListener(boundsListener)
                soloOverlayHeaderForBounds = header
            }
        }

        layered.add(overlay, JLayeredPane.POPUP_LAYER, 0)
        flushPendingWebViewEvents()

        frame?.revalidate()
        frame?.repaint()
        layoutSoloOverlay(layered, overlay)

        println("SoloMode: Solo overlay added below header (layeredPane POPUP_LAYER)")
    }

    /**
     * Solo 遮罩从标题栏（New UI [ToolbarFrameHeader]）下缘开始铺满，保留顶部标题栏区域可交互。
     */
    private fun layoutSoloOverlay(layered: JLayeredPane, overlay: Component) {
        val w = layered.width.coerceAtLeast(0)
        val h = layered.height.coerceAtLeast(0)
        val top = headerBottomYInLayeredPane(layered).coerceIn(0, h)
        val overlayH = (h - top).coerceAtLeast(0)
        overlay.setBounds(0, top, w, overlayH)
    }

    private fun headerBottomYInLayeredPane(layered: JLayeredPane): Int {
        val frameComp = ideFrame?.component as? Container ?: return 0
        val header = findToolbarFrameHeader(frameComp) ?: return 0
        if (!header.isShowing || header.height <= 0) return 0
        return try {
            SwingUtilities.convertPoint(header, 0, header.height, layered).y
        } catch (_: Throwable) {
            0
        }
    }

    private fun removeSoloModeUI() {
        if (rootPane == null) return

        soloModePanel?.saveSplitterProportion()
        soloModePanel?.restoreEditorComponent()
        soloModePanel?.disposeWebView()
        soloModePanel = null

        val layered = soloOverlayLayeredPane
        val overlay = soloOverlayRoot
        val listener = soloOverlayBoundsListener

        if (listener != null) {
            try {
                layered?.removeComponentListener(listener)
            } catch (_: Exception) {
            }
            try {
                soloOverlayHeaderForBounds?.removeComponentListener(listener)
            } catch (_: Exception) {
            }
        }
        soloOverlayBoundsListener = null
        soloOverlayHeaderForBounds = null

        if (layered != null && overlay != null) {
            try {
                layered.remove(overlay)
            } catch (e: Exception) {
                println("SoloMode: remove overlay failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        soloOverlayRoot = null
        soloOverlayLayeredPane = null

        frame?.revalidate()
        frame?.repaint()

        rootPane = null
        frame = null
        editorComponent = null
        editorParent = null
        editorConstraints = null
    }

    private fun describeComponent(c: Component?): String {
        if (c == null) return "null"
        return buildString {
            append(c.javaClass.name)
            append("@")
            append(Integer.toHexString(System.identityHashCode(c)))
            append("[visible=${c.isVisible}, displayable=${c.isDisplayable}")
            if (c is Container) append(", children=${c.componentCount}")
            append("]")
        }
    }

    private fun restoreUIComponents() {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        for ((id, wasVisible) in storedToolWindowStates) {
            val toolWindow = toolWindowManager.getToolWindow(id)
            if (toolWindow != null && wasVisible) {
                toolWindow.show()
            }
        }
        storedToolWindowStates.clear()

        val statusBar = ideFrame?.statusBar
        statusBar?.component?.isVisible = wasStatusBarVisible

        restoreToolbarButtons()
        restoreMainMenuButtons()
    }

    /** 恢复 toolbar 组件的 visible 到原始状态，组件顺序未被改动 */
    private fun restoreToolbarButtons() {
        uninstallToolbarNewComponentBlockers()

        for (toolbar in storedToolbars) {
            for (i in 0 until toolbar.componentCount) {
                val c = toolbar.getComponent(i)
                c.isVisible = toolbarComponentHiddenOriginalVisible[c] ?: true
            }
            toolbar.updateUI()
            toolbar.revalidate()
            toolbar.repaint()
        }

        storedToolbars.clear()
        originalToolbarComponents.clear()
        toolbarComponentHiddenOriginalVisible.clear()
    }

    private fun restoreMainMenuButtons() {
        storedMainMenuButton?.let { button ->
            if (button.isDisplayable) {
                button.isVisible = true
                button.parent?.revalidate()
                button.parent?.repaint()
            }
        }
        storedMainMenuButton = null
    }

    override fun dispose() {
        try {
            if (isSoloModeActive) {
                doExitSoloMode()
            }
        } catch (e: Exception) {
            println("SoloMode: Error in dispose: ${e.message}")
        }
    }

    companion object {
        private const val MAIN_MENU_BUTTON_EXACT_CLASS =
            "com.intellij.openapi.wm.impl.customFrameDecorations.header.toolbar.MainMenuButtonKt\$createMenuButton\$button\$1"
        private const val MAIN_MENU_BUTTON_CLASS_SUBSTRING = "MainMenuButtonKt\$createMenuButton\$button"

        fun getInstance(project: Project): SoloModeManager {
            return project.getService(SoloModeManager::class.java)
        }
    }
}

