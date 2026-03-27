package com.huawei.agenticmode.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.ui.tabs.impl.JBTabsImpl
import java.awt.Component
import java.awt.Container
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Replaces the editor tab popup at the JBTabs level instead of racing the platform's tab-label mouse listeners.
 * This avoids the original popup flashing briefly before our filtered popup appears.
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
    private val popupBindings = WeakHashMap<JBTabsImpl, PopupBinding>()

    /**
     * Allowed action ids in the tab popup.
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

        val root = currentRoot ?: safeGetRootContainer() ?: return
        restorePopupGroupsInSubtree(root)
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
            restorePopupGroupsInSubtree(root)
        }

        popupBindings.clear()
        rootProvider = null
    }

    private fun scheduleRefresh() {
        ApplicationManager.getApplication().invokeLater {
            if (!installed || project.isDisposed) return@invokeLater
            rebindRootIfNeeded()
            val root = currentRoot ?: return@invokeLater
            cleanupBindingsOutsideRoot(root)

            if (!enabled.get()) return@invokeLater
            applyPopupGroupsInSubtree(root)
        }
    }

    private fun rebindRootIfNeeded() {
        if (!installed || project.isDisposed) return

        val newRoot = safeGetRootContainer()
        if (newRoot === currentRoot) return

        currentRoot?.let { restorePopupGroupsInSubtree(it) }
        currentRoot = newRoot
        if (newRoot != null) {
            cleanupBindingsOutsideRoot(newRoot)
        } else {
            popupBindings.clear()
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

    private fun applyPopupGroupsInSubtree(component: Component) {
        val originalGroup = findOriginalEditorTabPopupGroup() ?: return

        traverse(component) { c ->
            val tabs = c as? JBTabsImpl ?: return@traverse
            attachPopupGroupToTabs(tabs, originalGroup)
        }
    }

    private fun restorePopupGroupsInSubtree(component: Component) {
        traverse(component) { c ->
            val tabs = c as? JBTabsImpl ?: return@traverse
            restorePopupGroup(tabs)
        }
    }

    private fun cleanupBindingsOutsideRoot(root: Component) {
        val liveTabs = Collections.newSetFromMap(IdentityHashMap<JBTabsImpl, Boolean>())
        traverse(root) { c ->
            val tabs = c as? JBTabsImpl ?: return@traverse
            liveTabs.add(tabs)
        }

        val iterator = popupBindings.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val tabs = entry.key ?: continue
            if (tabs !in liveTabs) {
                restorePopupGroup(tabs)
                iterator.remove()
            }
        }
    }

    private fun attachPopupGroupToTabs(tabs: JBTabsImpl, originalGroup: ActionGroup) {
        val binding = popupBindings.getOrPut(tabs) {
            PopupBinding(
                originalGroup = tabs.popupGroup ?: originalGroup,
                originalPlace = tabs.popupPlace,
                originalAddNavigationGroup = tabs.addNavigationGroup
            )
        }

        if (binding.applied) return

        val wrappedGroup = WhitelistAwareActionGroup(
            delegate = originalGroup,
            policy = PopupWhitelistPolicy(
                allowedActionIds = allowedActionIds,
                allowedGroupIds = allowedGroupIds
            )
        )

        try {
            tabs.setPopupGroup(
                wrappedGroup,
                binding.originalPlace ?: ActionPlaces.EDITOR_POPUP,
                binding.originalAddNavigationGroup
            )
            binding.applied = true
        } catch (_: Throwable) {
        }
    }

    private fun restorePopupGroup(tabs: JBTabsImpl) {
        val binding = popupBindings[tabs] ?: return
        if (!binding.applied) return

        try {
            tabs.setPopupGroup(
                binding.originalGroup,
                binding.originalPlace ?: ActionPlaces.EDITOR_POPUP,
                binding.originalAddNavigationGroup
            )
            binding.applied = false
        } catch (_: Throwable) {
        }
    }

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

    private data class PopupBinding(
        val originalGroup: ActionGroup,
        val originalPlace: String?,
        val originalAddNavigationGroup: Boolean,
        var applied: Boolean = false
    )

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
