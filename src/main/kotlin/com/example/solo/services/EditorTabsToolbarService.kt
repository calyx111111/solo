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
import java.awt.event.ContainerEvent
import java.awt.event.ContainerListener
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.BorderLayout
import javax.swing.*

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
    private val observedContainers = LinkedHashSet<Container>()

    private val containerListener = object : ContainerListener {
        override fun componentAdded(e: ContainerEvent) {
            val child = e.child ?: return

            if (child is Container) {
                installContainerListenersRecursively(child)
            }

            SwingUtilities.invokeLater {
                if (enabled.get()) {
                    processSubtree(child, enableMode = true)
                } else {
                    processSubtree(child, enableMode = false)
                }
            }
        }

        override fun componentRemoved(e: ContainerEvent) {
            val child = e.child ?: return
            if (child is Container) {
                uninstallContainerListenersRecursively(child)
            }
        }
    }

    fun install(rootProvider: () -> Component?) {
        this.rootProvider = rootProvider
        if (installed) {
            rebindRootIfNeeded()
            return
        }
        installed = true

        currentRoot = rootProvider() as? Container
        currentRoot?.let { installContainerListenersRecursively(it) }

        val callback: () -> Unit = { scheduleRefresh() }
        tabChangeCallback = callback
        project.service<FileEditorChangeDispatcher>().addCallback(callback)

        println("EditorTabsMoreToolbarController: installed")
    }

    fun rebindRootIfNeeded() {
        val provider = rootProvider ?: return
        val newRoot = provider() as? Container

        if (newRoot === currentRoot) {
            refreshNow()
            return
        }

        currentRoot?.let { uninstallContainerListenersRecursively(it) }
        currentRoot = newRoot
        currentRoot?.let { installContainerListenersRecursively(it) }

        refreshNow()
    }

    fun enable() {
        if (!installed) return

        if (enabled.compareAndSet(false, true)) {
            println("EditorTabsMoreToolbarController: enabled")
            refreshNow()
        }
    }

    fun disable() {
        if (enabled.compareAndSet(true, false)) {
            println("EditorTabsMoreToolbarController: disabled")
            refreshNow()
        }
    }

    fun isEnabled(): Boolean = enabled.get()

    override fun dispose() {
        if (!installed) return
        installed = false

        enabled.set(false)
        refreshNow()

        tabChangeCallback?.let { project.service<FileEditorChangeDispatcher>().removeCallback(it) }
        tabChangeCallback = null

        currentRoot?.let { uninstallContainerListenersRecursively(it) }
        currentRoot = null
        observedContainers.clear()

        println("EditorTabsMoreToolbarController: disposed")
    }

    private fun scheduleRefresh() {
        SwingUtilities.invokeLater {
            refreshNow()
        }
    }

    private fun refreshNow() {
        ApplicationManager.getApplication().invokeLater {
            val root = rootProvider?.invoke() ?: return@invokeLater
            processSubtree(root, enableMode = enabled.get())
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

    private fun installContainerListenersRecursively(container: Container) {
        if (!observedContainers.add(container)) return

        try {
            container.addContainerListener(containerListener)
        } catch (_: Throwable) {
        }

        container.components.forEach {
            if (it is Container) {
                installContainerListenersRecursively(it)
            }
        }
    }

    private fun uninstallContainerListenersRecursively(container: Container) {
        if (!observedContainers.remove(container)) return

        try {
            container.removeContainerListener(containerListener)
        } catch (_: Throwable) {
        }

        container.components.forEach {
            if (it is Container) {
                uninstallContainerListenersRecursively(it)
            }
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
