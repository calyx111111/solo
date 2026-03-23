package com.example.solo.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import java.awt.event.MouseEvent

@Service(Service.Level.PROJECT)
class EditorPopupService(
    private val project: Project
) : Disposable {

    @Volatile
    private var enabled: Boolean = false

    /**
     * 白名单规则：
     *
     * - allowedActionIds：匹配具体 action。
     *   命中后，该 action 显示，并自动保留其所在路径上的所有父 group。
     *
     * - allowedGroupIds：匹配 group。
     *   命中后，该 group 及其下面整棵子树全部放通。
     */

    /**
     * 允许显示的 action id
     */
    private val allowedActionIds = setOf(
        IdeActions.ACTION_COPY,
        IdeActions.ACTION_CUT,
        IdeActions.ACTION_DELETE,
        IdeActions.ACTION_PASTE,
        IdeActions.ACTION_REDO,
        IdeActions.ACTION_SELECT_ALL,
        IdeActions.ACTION_UNDO,
        "FindSelectionInPath"
    )

    /**
     * 显式允许显示的 group id
     */
    private val allowedGroupIds = setOf<String>(
//        "FoldingGroup",
//        IdeActions.GROUP_REFACTOR
    )

    private val editorMouseListener = object : EditorMouseListener {
        override fun mousePressed(event: EditorMouseEvent) {
            handlePopup(event)
        }

        override fun mouseReleased(event: EditorMouseEvent) {
            handlePopup(event)
        }
    }

    init {
        EditorFactory.getInstance().eventMulticaster.addEditorMouseListener(editorMouseListener, this)
    }

    fun enable() {
        enabled = true
    }

    fun disable() {
        enabled = false
    }

    override fun dispose() = Unit

    private fun handlePopup(event: EditorMouseEvent) {
        if (!enabled) return

        val editor = event.editor
        if (editor.project !== project) return
        if (event.area != EditorMouseEventArea.EDITING_AREA) return

        val mouseEvent = event.mouseEvent
        if (!mouseEvent.isPopupTrigger) return

        val originalGroup = ActionManager.getInstance()
            .getAction(IdeActions.GROUP_EDITOR_POPUP) as? ActionGroup
            ?: return

        mouseEvent.consume()
        moveCaretIfNeeded(editor, mouseEvent)

        val wrappedGroup = WhitelistAwareActionGroup(
            delegate = originalGroup,
            policy = PopupWhitelistPolicy(
                allowedActionIds = allowedActionIds,
                allowedGroupIds = allowedGroupIds,
            )
        )

        val popupMenu = ActionManager.getInstance()
            .createActionPopupMenu(ActionPlaces.EDITOR_POPUP, wrappedGroup)

        popupMenu.component.show(
            editor.contentComponent,
            mouseEvent.x,
            mouseEvent.y
        )
    }

    private fun moveCaretIfNeeded(editor: Editor, mouseEvent: MouseEvent) {
        val point = mouseEvent.point
        val visualPosition = editor.xyToVisualPosition(point)
        if (editor.caretModel.currentCaret.visualPosition != visualPosition) {
            editor.caretModel.moveToVisualPosition(visualPosition)
        }
    }

    private data class PopupWhitelistPolicy(
        val allowedActionIds: Set<String>,
        val allowedGroupIds: Set<String>,
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

    /**
     * 包一层 ActionGroup：
     * - 只基于"当前层 children"做过滤
     * - 不递归探测后代
     * - 保留合法分割线
     *
     * 关键点：
     * - 不再 clone event
     * - 不再在 update() 里调用 delegate.update(clonedEvent)
     */
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
            val children = delegate.getChildren(e)
            val filtered = ArrayList<AnAction>(children.size)

            // 当前 group 是否已经放通（父 group 放通 或 自己放通）
            val subtreeAllowed = subtreeAllowedFromParent || policy.isAllowedGroup(delegate)

            for (child in children) {
                when (child) {
                    is Separator -> filtered.add(child)
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

    /**
     * 普通 action 包装：
     * - 不再 clone event
     * - 直接使用平台原始 event
     */
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
        /**
         * 清理分割线：
         * - 去掉开头的 separator
         * - 去掉结尾的 separator
         * - 合并连续 separator
         */
        private fun normalizeSeparators(actions: List<AnAction>): List<AnAction> {
            val result = ArrayList<AnAction>(actions.size)
            var previousWasSeparator = true

            for (action in actions) {
                val isSeparator = action is Separator
                if (isSeparator) {
                    if (!previousWasSeparator) result.add(action)
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
            } catch (t: Throwable) {
                return false
            }

            for (child in children) {
                when (child) {
                    is Separator -> continue
                    is ActionGroup -> if (shouldKeepGroup(child, e, policy, depth + 1, maxDepth)) return true
                    else -> if (policy.isAllowedAction(child)) return true
                }
            }

            return false
        }
    }
}
