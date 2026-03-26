package com.huawei.agenticmode.services

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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities

/**
 * Solo 模式下在编辑器 Tab 上提供白名单右键菜单；通过 [enable]/[disable] 控制是否拦截弹出。
 * Tab / 编辑器结构变化依赖 [FileEditorChangeDispatcher]（与 [EditorTabDragLockService] 相同），不在此挂 ContainerListener。
 */
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

    fun install(rootProvider: () -> Component?) {
        this.rootProvider = rootProvider

        if (!installed) {
            installed = true
            currentRoot = safeGetRootContainer()

            val callback: () -> Unit = { scheduleRefresh() }
            tabChangeCallback = callback
            project.service<FileEditorChangeDispatcher>().addCallback(callback)
        } else {
            rebindRootIfNeeded()
        }
    }

    fun enable() {
        if (!installed || project.isDisposed) return
        enabled.set(true)
        scheduleRefresh()
    }

    fun disable() {
        if (!installed || project.isDisposed) return
        enabled.set(false)
    }

    fun isEnabled(): Boolean = enabled.get()

    override fun dispose() {
        installed = false
        enabled.set(false)

        tabChangeCallback?.let { project.service<FileEditorChangeDispatcher>().removeCallback(it) }
        tabChangeCallback = null

        val root = currentRoot ?: safeGetRootContainer()
        currentRoot = null
        if (root != null) {
            removeOurPopupListenersInSubtree(root)
        }

        rootProvider = null
    }

    private fun scheduleRefresh() {
        ApplicationManager.getApplication().invokeLater {
            if (!installed || project.isDisposed) return@invokeLater
            val root = currentRoot ?: safeGetRootContainer() ?: return@invokeLater
            attachPopupListenersInSubtree(root)
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

    private fun attachPopupListenersInSubtree(component: Component) {
        traverse(component) { c ->
            val jc = c as? JComponent ?: return@traverse
            if (!isEditorTabLabel(jc)) return@traverse
            attachPopupListenerToTabLabel(jc)
        }
    }

    private fun attachPopupListenerToTabLabel(tabLabel: JComponent) {
        val already = tabLabel.mouseListeners.firstOrNull { it is SoloEditorTabPopupMouseListener } as? SoloEditorTabPopupMouseListener
        if (already != null) {
            return
        }

        val listener = SoloEditorTabPopupMouseListener(tabLabel)

        try {
            tabLabel.addMouseListener(listener)
        } catch (_: Throwable) {
        }
    }

    private fun removeOurPopupListenersInSubtree(component: Component) {
        traverse(component) { c ->
            val jc = c as? JComponent ?: return@traverse
            if (!isEditorTabLabel(jc)) return@traverse
            val ours = jc.mouseListeners.filterIsInstance<SoloEditorTabPopupMouseListener>()
            for (l in ours) {
                try {
                    jc.removeMouseListener(l)
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** 用于在 [JComponent.mouseListeners] 中识别本服务注册的监听，避免重复 add。 */
    private inner class SoloEditorTabPopupMouseListener(
        private val tabLabel: JComponent
    ) : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            handlePopup(tabLabel, e)
        }

        override fun mouseReleased(e: MouseEvent) {
            handlePopup(tabLabel, e)
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
