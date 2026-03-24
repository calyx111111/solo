package com.example.solo.services

import com.example.solo.SoloModePanel
import com.example.solo.actions.ToggleProjectPanelAction
import com.example.solo.actions.scaledTo
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.ide.actions.TabListAction
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.awt.Component
import java.awt.Container
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.BorderLayout
import javax.swing.*

/**
 * Solo 模式下改造编辑器 Tabs 右侧 “更多” 工具栏；通过 [enable]/[disable] 控制。
 * Tab 变化依赖 [FileEditorChangeDispatcher]，与 [EditorTabDragLockService] 相同，不在此挂 ContainerListener。
 */
@Service(Service.Level.PROJECT)
class EditorTabsToolbarService(
    private val project: Project
) : Disposable {

    companion object {
        private const val PLACEHOLDER_NAME = "SoloTabsMoreToolbarPlaceholder"
        private const val SOLO_MD_SEPARATOR_NAME = "SoloTabsMoreToolbarMdSeparator"
    }

    private var rootProvider: (() -> Component?)? = null
    private val enabled = AtomicBoolean(false)
    private var installed = false
    private var currentRoot: Container? = null
    private var tabChangeCallback: (() -> Unit)? = null

    fun install(rootProvider: () -> Component?) {
        this.rootProvider = rootProvider

        if (!installed) {
            installed = true
            currentRoot = safeGetRootContainer()

            val callback: () -> Unit = { refreshWhenTabsChanged() }
            tabChangeCallback = callback
            project.service<FileEditorChangeDispatcher>().addCallback(callback)
        } else {
            rebindRootIfNeeded()
        }
    }

    fun enable() {
        if (!installed || project.isDisposed) return
        enabled.set(true)
        refreshNow()
    }

    fun disable() {
        if (!installed || project.isDisposed) return
        enabled.set(false)
        refreshNow()
    }

    fun isEnabled(): Boolean = enabled.get()

    override fun dispose() {
        installed = false
        enabled.set(false)

        tabChangeCallback?.let { project.service<FileEditorChangeDispatcher>().removeCallback(it) }
        tabChangeCallback = null

        refreshNow()

        currentRoot = null
        rootProvider = null
    }

    private fun refreshWhenTabsChanged() {
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        SwingUtilities.invokeLater {
            refreshNow()
        }
    }

    private fun refreshNow() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val root = currentRoot ?: safeGetRootContainer() ?: return@invokeLater
            processSubtree(root, enableMode = enabled.get())
        }
    }

    private fun rebindRootIfNeeded() {
        if (!installed || project.isDisposed) return

        val newRoot = safeGetRootContainer()
        if (newRoot === currentRoot) return

        currentRoot = newRoot
    }

    private fun safeGetRootContainer(): Container? {
        val provider = rootProvider ?: return null
        return try {
            provider.invoke() as? Container
        } catch (_: Throwable) {
            null
        }
    }

    private fun processSubtree(component: Component, enableMode: Boolean) {
        traverse(component) { c ->
            val toolbar = c as? ActionToolbarImpl ?: return@traverse
            if (!isTabsMoreToolbar(toolbar)) return@traverse

            if (enableMode) {
                enableToolbar(toolbar)
            } else {
                disableToolbar(toolbar)
            }
        }
    }

    private fun isTabsMoreToolbar(toolbar: ActionToolbarImpl): Boolean {
        val place = getToolbarPlace(toolbar) ?: return false
        if (place != "TabsMoreToolbar") return false

        val parent = toolbar.parent ?: return false
        return parent.javaClass.simpleName == "EditorTabs"
    }

    private fun enableToolbar(toolbar: ActionToolbarImpl) {
        // 展开more tab页按钮在的toolbar不执行操作
        toolbar.components.forEach { child ->
            if (isTabListActionButton(child)) {
                return
            }
        }

        if (toolbar.components.any { isMarkdownPreviewLayoutButton(it) }) {
            ensureMdSeparator(toolbar)
        }

        val placeholder = ensurePlaceholder(toolbar)

        toolbar.components.forEach { child ->
            when {
                child === placeholder -> child.isVisible = true
                isSoloMdSeparator(child) -> child.isVisible = true
                isMarkdownPreviewLayoutButton(child) -> child.isVisible = true
                else -> child.isVisible = false
            }
        }

        toolbar.revalidate()
        toolbar.repaint()
        toolbar.parent?.revalidate()
        toolbar.parent?.repaint()
    }

    private fun disableToolbar(toolbar: ActionToolbarImpl) {
        val placeholder = findPlaceholderInToolbar(toolbar)

        toolbar.components.forEach { child ->
            when {
                child === placeholder -> child.isVisible = false
                isSoloMdSeparator(child) -> child.isVisible = false
                else -> child.isVisible = true
            }
        }

        toolbar.revalidate()
        toolbar.repaint()
        toolbar.parent?.revalidate()
        toolbar.parent?.repaint()
    }

    private fun ensurePlaceholder(toolbar: ActionToolbarImpl): JComponent {
        val existing = findPlaceholderInToolbar(toolbar)
        if (existing != null) return existing

        val placeholder = createFixedPlaceholder(toolbar)
        toolbar.add(placeholder)
        return placeholder
    }

    private fun ensureMdSeparator(toolbar: ActionToolbarImpl): JComponent {
        val existing = findMdSeparatorInToolbar(toolbar)
        if (existing != null) return existing

        val strutSize = JBUI.scale(6)
        val line = JSeparator(SwingConstants.VERTICAL).apply {
            isOpaque = false
        }
        val wrapper = object : JPanel(BorderLayout()) {
            override fun isVisible(): Boolean {
                return super.isVisible() && enabled.get() && SoloModePanel.isProjectCollapsed(project)
            }
        }.apply {
            name = SOLO_MD_SEPARATOR_NAME
            add(Box.createHorizontalStrut(strutSize), BorderLayout.WEST)
            add(line, BorderLayout.CENTER)
            add(Box.createHorizontalStrut(strutSize), BorderLayout.EAST)
            isOpaque = false
            isVisible = true
            minimumSize = JBUI.size(14, 20)
            preferredSize = JBUI.size(14, 24)
            maximumSize = JBUI.size(14, 24)
        }
        toolbar.add(wrapper)
        return wrapper
    }

    private fun findMdSeparatorInToolbar(toolbar: ActionToolbarImpl): JComponent? {
        toolbar.components.forEach { child ->
            if (isSoloMdSeparator(child)) {
                return child as? JComponent
            }
        }
        return null
    }

    private fun isSoloMdSeparator(component: Component?): Boolean {
        val jc = component as? JComponent ?: return false
        return jc.name == SOLO_MD_SEPARATOR_NAME
    }

    private fun findPlaceholderInToolbar(toolbar: ActionToolbarImpl): JComponent? {
        toolbar.components.forEach { child ->
            if (isSoloTabsMoreToolbarPlaceholder(child)) {
                return child as? JComponent
            }
        }
        return null
    }

    private fun isSoloTabsMoreToolbarPlaceholder(component: Component?): Boolean {
        val jc = component as? JComponent ?: return false
        return jc.name == PLACEHOLDER_NAME
    }

    private fun isTabListActionButton(component: Component?): Boolean {
        val button = component as? ActionButton ?: return false
        return button.action is TabListAction
    }

    private fun isMarkdownPreviewLayoutButton(component: Component?): Boolean {
        val button = component as? ActionButton ?: return false
        val className = button.action.javaClass.name
        return className.startsWith("com.intellij.openapi.fileEditor.ChangePreviewLayoutAction$")
    }

    private fun createFixedPlaceholder(toolbar: ActionToolbarImpl): JComponent {
        val baseIcon = IconLoader.getIcon("/icon/toggle_right.svg", javaClass).scaledTo(16, 16)
        val presentation = Presentation().apply {
            icon = IconLoader.getDarkIcon(baseIcon, !JBColor.isBright())
            text = "Show Project"
            description = "Show Project Panel"
            isEnabled = true
            isVisible = true
        }

        val action = ToggleProjectPanelAction(project, ToggleProjectPanelAction.DisplayMode.COLLAPSED)

        val button = ActionButton(
            action,
            presentation,
            toolbar.place.ifBlank { ActionPlaces.UNKNOWN },
            toolbar.minimumButtonSize
        ).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            isVisible = true
        }

        return object : JPanel(BorderLayout()) {
            override fun isVisible(): Boolean {
                return super.isVisible() && enabled.get() && SoloModePanel.isProjectCollapsed(project)
            }
        }.apply {
            name = PLACEHOLDER_NAME
            isOpaque = false
            isVisible = false
            border = JBUI.Borders.empty()
            add(button, BorderLayout.CENTER)

            preferredSize = button.preferredSize
            minimumSize = button.minimumSize
            maximumSize = button.maximumSize
        }
    }

    private fun traverse(component: Component, visit: (Component) -> Unit) {
        visit(component)
        if (component is Container) {
            component.components.forEach { traverse(it, visit) }
        }
    }

    private fun getToolbarPlace(toolbar: ActionToolbarImpl): String? {
        return try {
            toolbar.place
        } catch (_: Throwable) {
            try {
                val method = toolbar.javaClass.methods.firstOrNull {
                    it.name == "getPlace" && it.parameterCount == 0
                }
                method?.invoke(toolbar)?.toString()
            } catch (_: Throwable) {
                null
            }
        }
    }
}
