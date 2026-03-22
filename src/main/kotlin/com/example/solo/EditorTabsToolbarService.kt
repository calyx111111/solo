package com.example.solo

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.TabListAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import java.awt.Component
import java.awt.Container
import java.awt.event.ContainerEvent
import java.awt.event.ContainerListener
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.SwingUtilities

@Service(Service.Level.PROJECT)
class EditorTabsToolbarService(
    private val project: Project
) : Disposable {

    companion object {
        private const val PLACEHOLDER_NAME = "SoloTabsMoreToolbarPlaceholder"
    }

    private var rootProvider: (() -> Component?)? = null
    private val enabled = AtomicBoolean(false)
    private var installed = false
    private var currentRoot: Container? = null
    private var messageBusConnection: MessageBusConnection? = null
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

        messageBusConnection = project.messageBus.connect().also { connection ->
            connection.subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun fileOpened(
                        source: FileEditorManager,
                        file: com.intellij.openapi.vfs.VirtualFile
                    ) {
                        scheduleRefresh()
                    }

                    override fun fileClosed(
                        source: FileEditorManager,
                        file: com.intellij.openapi.vfs.VirtualFile
                    ) {
                        scheduleRefresh()
                    }

                    override fun selectionChanged(event: FileEditorManagerEvent) {
                        scheduleRefresh()
                    }
                }
            )
        }

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

        messageBusConnection?.disconnect()
        messageBusConnection = null

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

        val placeholder = ensurePlaceholder(toolbar)

        toolbar.components.forEach { child ->
            if (child === placeholder) {
                child.isVisible = true
            } else {
                child.isVisible = false
            }
        }

        toolbar.revalidate()
        toolbar.repaint()
        toolbar.parent?.revalidate()
        toolbar.parent?.repaint()

//        println(
//            "EditorTabsMoreToolbarController: enable toolbar=" +
//                    "${toolbar.javaClass.name}@${Integer.toHexString(System.identityHashCode(toolbar))}, " +
//                    "childCount=${toolbar.componentCount}"
//        )
    }

    private fun disableToolbar(toolbar: ActionToolbarImpl) {
        val placeholder = findPlaceholderInToolbar(toolbar)

        toolbar.components.forEach { child ->
            if (child === placeholder) {
                child.isVisible = false
            } else {
                child.isVisible = true
            }
        }

        toolbar.revalidate()
        toolbar.repaint()
        toolbar.parent?.revalidate()
        toolbar.parent?.repaint()

//        println(
//            "EditorTabsMoreToolbarController: disable toolbar=" +
//                    "${toolbar.javaClass.name}@${Integer.toHexString(System.identityHashCode(toolbar))}, " +
//                    "childCount=${toolbar.componentCount}"
//        )
    }

    private fun ensurePlaceholder(toolbar: ActionToolbarImpl): JComponent {
        val existing = findPlaceholderInToolbar(toolbar)
        if (existing != null) return existing

        val placeholder = createFixedPlaceholder(toolbar)
        toolbar.add(placeholder)
        return placeholder
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

    private fun createFixedPlaceholder(toolbar: ActionToolbarImpl): JComponent {
        val actionGroup = DefaultActionGroup().apply {
            add(SoloTabsMoreToolbarAction())
        }

        val miniToolbar = ActionManager.getInstance().createActionToolbar(
            "SoloTabsMoreToolbarReplacement",
            actionGroup,
            true
        )

        miniToolbar.setReservePlaceAutoPopupIcon(false)
        miniToolbar.targetComponent = toolbar.targetComponent ?: (toolbar.parent as? JComponent)

        return miniToolbar.component.apply {
            name = PLACEHOLDER_NAME
            isOpaque = false
            isVisible = false
        }
    }

    private inner class SoloTabsMoreToolbarAction : AnAction(), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = SoloModePanel.isProjectCollapsed(project)
            e.presentation.text = "Solo"
            e.presentation.description = "Show Project Panel"
            e.presentation.icon = AllIcons.Actions.SplitVertically
        }

        override fun actionPerformed(e: AnActionEvent) {
            SoloModeManager.getInstance(project).toggleProjectPanel()
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