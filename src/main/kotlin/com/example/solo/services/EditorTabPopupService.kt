package com.example.solo.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.ContainerEvent
import java.awt.event.ContainerListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Collections
import java.util.LinkedHashSet
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities

@Service(Service.Level.PROJECT)
class EditorTabPopupService(
    private val project: Project
) : Disposable {

    private var rootProvider: (() -> Component?)? = null
    private val enabled = AtomicBoolean(false)
    private var installed = false
    private var currentRoot: Container? = null
    private var tabChangeCallback: (() -> Unit)? = null

    /**
     * 已安装过 ContainerListener 的容器
     */
    private val observedContainers = LinkedHashSet<Container>()

    /**
     * 只用于"防重复安装 listener"，不要强引用 tabLabel
     */
    private val observedTabLabels: MutableSet<JComponent> =
        Collections.newSetFromMap(WeakHashMap())

    /**
     * dispose 时需要统一 remove listener，所以这里仍然保留映射。
     * 运行期不主动 detach，不做 cleanupDetachedTabListeners。
     */
    private val installedPopupListeners = WeakHashMap<JComponent, MouseAdapter>()

    /**
     * 防抖：避免短时间重复 refresh
     */
    @Volatile
    private var refreshScheduled = false

    /**
     * 白名单 action id
     */
    private val allowedActionIds = linkedSetOf(
        IdeActions.ACTION_CLOSE,
        IdeActions.ACTION_CLOSE_ALL_EDITORS,
        IdeActions.ACTION_CLOSE_ALL_EDITORS_BUT_THIS,
        "CopyPaths",
        "CopyReference",
        "CopyAbsolutePath",
        "CopyFileName",
        "CopyPathWithLineNumber",
        "CopyContentRootPath",
        "CopySourceRootPath",
        "CopyPathFromRepositoryRootProvider",
        "ReopenClosedTab"
    )

    /**
     * 白名单 group id
     */
    private val allowedGroupIds = linkedSetOf<String>(
//        "SomeEditorTabPopupGroup"
    )

    private val containerListener = object : ContainerListener {
        override fun componentAdded(e: ContainerEvent) {
            if (!installed || project.isDisposed) return

            val child = e.child ?: return

            if (child is Container) {
                installContainerListenersRecursively(child)
            }

            SwingUtilities.invokeLater {
                if (!installed || project.isDisposed) return@invokeLater
                attachPopupListenersInSubtree(child)
            }
        }

        override fun componentRemoved(e: ContainerEvent) {
            if (!installed || project.isDisposed) return

            val child = e.child ?: return

            if (child is Container) {
                uninstallContainerListenersRecursively(child)
            }

            // 不主动 detach tab listener
        }
    }

    fun install(rootProvider: () -> Component?) {
        this.rootProvider = rootProvider

        if (!installed) {
            installed = true

            currentRoot = safeGetRootContainer()
            currentRoot?.let {
                installContainerListenersRecursively(it)
                attachPopupListenersInSubtree(it)
            }

            val callback: () -> Unit = { scheduleRefresh() }
            tabChangeCallback = callback
            project.service<FileEditorChangeDispatcher>().addCallback(callback)
        } else {
            rebindRootIfNeeded()
        }

        scheduleRefresh()
    }

    fun enable() {
        if (!installed || project.isDisposed) return
        enabled.set(true)
    }

    fun disable() {
        if (!installed || project.isDisposed) return
        enabled.set(false)
    }

    fun isEnabled(): Boolean = enabled.get()

    override fun dispose() {
        installed = false
        enabled.set(false)
        refreshScheduled = false

        tabChangeCallback?.let { project.service<FileEditorChangeDispatcher>().removeCallback(it) }
        tabChangeCallback = null

        currentRoot?.let { uninstallContainerListenersRecursively(it) }
        currentRoot = null
        observedContainers.clear()

        uninstallAllTabMouseListeners()

        observedTabLabels.clear()
        rootProvider = null
    }

    private fun scheduleRefresh() {
        if (!installed || project.isDisposed) return
        if (refreshScheduled) return

        refreshScheduled = true
        ApplicationManager.getApplication().invokeLater {
            refreshScheduled = false

            if (!installed || project.isDisposed) return@invokeLater
            refreshNow()
        }
    }

    private fun refreshNow() {
        if (!installed || project.isDisposed) return

        rebindRootIfNeeded()

        val root = currentRoot ?: return
        attachPopupListenersInSubtree(root)
    }

    private fun rebindRootIfNeeded() {
        if (!installed || project.isDisposed) return

        val newRoot = safeGetRootContainer()
        if (newRoot === currentRoot) return

        currentRoot?.let { uninstallContainerListenersRecursively(it) }
        currentRoot = newRoot
        currentRoot?.let {
            installContainerListenersRecursively(it)
            attachPopupListenersInSubtree(it)
        }
    }

    private fun safeGetRootContainer(): Container? {
        val provider = rootProvider ?: return null
        return try {
            provider.invoke() as? Container
        } catch (_: Throwable) {
            null
        }
    }

    private fun attachPopupListenersInSubtree(component: Component) {
        traverse(component) { c ->
            val jc = c as? JComponent ?: return@traverse
            if (!isEditorTabLabel(jc)) return@traverse
            attachPopupListenerToTabLabel(jc)
        }
    }

    private fun attachPopupListenerToTabLabel(tabLabel: JComponent) {
        if (!observedTabLabels.add(tabLabel)) return

        val listener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                handlePopup(tabLabel, e)
            }

            override fun mouseReleased(e: MouseEvent) {
                handlePopup(tabLabel, e)
            }
        }

        try {
            tabLabel.addMouseListener(listener)
            installedPopupListeners[tabLabel] = listener
        } catch (_: Throwable) {
            observedTabLabels.remove(tabLabel)
        }
    }

    private fun uninstallAllTabMouseListeners() {
        val entries = installedPopupListeners.entries.toList()
        installedPopupListeners.clear()

        for ((tabLabel, listener) in entries) {
            try {
                tabLabel.removeMouseListener(listener)
            } catch (_: Throwable) {
            }
        }
    }

    private fun handlePopup(tabLabel: JComponent, mouseEvent: MouseEvent) {
        if (!installed || project.isDisposed) return
        if (!enabled.get()) return
        if (!mouseEvent.isPopupTrigger) return
        if (!tabLabel.isShowing) return

        val originalGroup = findOriginalEditorTabPopupGroup() ?: return

        mouseEvent.consume()

        val wrappedGroup = WhitelistAwareActionGroup(
            delegate = originalGroup,
            policy = PopupWhitelistPolicy(
                allowedActionIds = allowedActionIds,
                allowedGroupIds = allowedGroupIds
            )
        )

        val popupMenu = ActionManager.getInstance()
            .createActionPopupMenu(ActionPlaces.EDITOR_POPUP, wrappedGroup)

        val point = normalizePointForComponent(tabLabel, mouseEvent)

        // 与 EditorTabLabel 上平台自带的 MouseListener 同级；consume() 不能阻止对方在同一事件里先弹出菜单。
        // 弹出前 clear，把已经激活的菜单清除，避免闪烁弹出原本的右键菜单
        SwingUtilities.invokeLater {
            MenuSelectionManager.defaultManager().clearSelectedPath()
            popupMenu.component.show(tabLabel, point.x, point.y)
        }
    }


    /**
     * 不同平台版本 group id 可能不一样，这里多试几个
     */
    private fun findOriginalEditorTabPopupGroup(): ActionGroup? {
        val actionManager = ActionManager.getInstance()

        val candidates = listOf(
            "EditorTabPopupMenu",
            "EditorTabsPopupMenu",
            "EditorTabPopup",
            "EditorTabsPopup",
            IdeActions.GROUP_EDITOR_TAB_POPUP
        )

        for (id in candidates) {
            val action = try {
                actionManager.getAction(id)
            } catch (_: Throwable) {
                null
            }

            if (action is ActionGroup) {
                return action
            }
        }

        return null
    }

    private fun normalizePointForComponent(component: JComponent, event: MouseEvent): Point {
        return try {
            SwingUtilities.convertPoint(event.component, event.point, component)
        } catch (_: Throwable) {
            event.point
        }
    }

    private fun isEditorTabLabel(component: JComponent): Boolean {
        val simpleName = component.javaClass.simpleName
        val className = component.javaClass.name

        if (simpleName == "EditorTabLabel") return true
        if (className.endsWith(".EditorTabLabel")) return true
        if (simpleName.contains("EditorTabLabel")) return true
        if (className.contains("EditorTabLabel")) return true

        return false
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

    private data class PopupWhitelistPolicy(
        val allowedActionIds: Set<String>,
        val allowedGroupIds: Set<String>
    ) {
        fun actionIdOf(action: AnAction): String? {
            return ActionManager.getInstance().getId(action)
        }

        fun isAllowedAction(action: AnAction): Boolean {
            val actionId = actionIdOf(action)
            return actionId != null && actionId in allowedActionIds
        }

        fun isAllowedGroup(group: ActionGroup): Boolean {
            val actionId = actionIdOf(group) ?: return false
            return actionId in allowedGroupIds
        }
    }

    private class WhitelistAwareActionGroup(
        private val delegate: ActionGroup,
        private val policy: PopupWhitelistPolicy,
        private val subtreeAllowedFromParent: Boolean = false
    ) : ActionGroup(
        delegate.templatePresentation.text,
        delegate.templatePresentation.description,
        delegate.templatePresentation.icon
    ), DumbAware {

        init {
            copyFrom(delegate)
            shortcutSet = delegate.shortcutSet
            isPopup = delegate.isPopup
        }

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val children = try {
                delegate.getChildren(e)
            } catch (_: Throwable) {
                emptyArray()
            }

            val filtered = ArrayList<AnAction>(children.size)

            val subtreeAllowed = subtreeAllowedFromParent || policy.isAllowedGroup(delegate)

            for (child in children) {
                when (child) {
                    is Separator -> {
                        filtered.add(child)
                    }

                    is ActionGroup -> {
                        val keep = subtreeAllowed || shouldKeepGroup(child, e, policy)
                        if (keep) {
                            filtered.add(
                                WhitelistAwareActionGroup(
                                    delegate = child,
                                    policy = policy,
                                    subtreeAllowedFromParent = subtreeAllowed
                                )
                            )
                        }
                    }

                    else -> {
                        if (subtreeAllowed || policy.isAllowedAction(child)) {
                            filtered.add(WhitelistAwareAction(child, policy))
                        }
                    }
                }
            }

            return normalizeSeparators(filtered).toTypedArray()
        }

        override fun update(e: AnActionEvent) {
            val visibleChildren = getChildren(e)
            e.presentation.text = templatePresentation.text
            e.presentation.description = templatePresentation.description
            e.presentation.icon = templatePresentation.icon
            e.presentation.isVisible = visibleChildren.isNotEmpty()
            e.presentation.isEnabled = visibleChildren.isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) = Unit

        override fun getActionUpdateThread(): ActionUpdateThread = delegate.actionUpdateThread
    }

    private class WhitelistAwareAction(
        private val delegate: AnAction,
        private val policy: PopupWhitelistPolicy
    ) : AnAction(
        delegate.templatePresentation.text,
        delegate.templatePresentation.description,
        delegate.templatePresentation.icon
    ), DumbAware {

        init {
            copyFrom(delegate)
            shortcutSet = delegate.shortcutSet
        }

        override fun update(e: AnActionEvent) {
            delegate.update(e)
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (!policy.isAllowedAction(delegate)) return
            if (!ActionUtil.lastUpdateAndCheckDumb(delegate, e, false)) return
            delegate.actionPerformed(e)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = delegate.actionUpdateThread
    }

    companion object {
        fun getInstance(project: Project): EditorTabPopupService {
            return project.service()
        }

        private fun normalizeSeparators(actions: List<AnAction>): List<AnAction> {
            val result = ArrayList<AnAction>(actions.size)
            var previousWasSeparator = true

            for (action in actions) {
                val isSeparator = action is Separator
                if (isSeparator) {
                    if (!previousWasSeparator) {
                        result.add(action)
                    }
                } else {
                    result.add(action)
                }
                previousWasSeparator = isSeparator
            }

            while (result.isNotEmpty() && result.last() is Separator) {
                result.removeAt(result.lastIndex)
            }

            return result
        }

        private fun shouldKeepGroup(
            group: ActionGroup,
            e: AnActionEvent?,
            policy: PopupWhitelistPolicy,
            depth: Int = 0,
            maxDepth: Int = 8
        ): Boolean {
            if (depth > maxDepth) return false
            if (policy.isAllowedGroup(group)) return true

            val children = try {
                group.getChildren(e)
            } catch (_: Throwable) {
                return false
            }

            for (child in children) {
                when (child) {
                    is Separator -> continue
                    is ActionGroup -> {
                        if (shouldKeepGroup(child, e, policy, depth + 1, maxDepth)) {
                            return true
                        }
                    }

                    else -> {
                        if (policy.isAllowedAction(child)) {
                            return true
                        }
                    }
                }
            }

            return false
        }
    }
}
