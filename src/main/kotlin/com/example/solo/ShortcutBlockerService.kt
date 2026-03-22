package com.example.solo

import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * 单文件版：
 * - Project service 管理本 project 是否启用
 * - Application service 只挂一个全局 dispatcher
 * - 所有 project 使用固定白名单
 */

/* ----------------------------- project service ----------------------------- */

@Service(Service.Level.PROJECT)
class ShortcutBlockerService(
    private val project: Project
) : Disposable {

    companion object {
        /** 内置固定白名单 */
        val WHITELIST_ACTION_IDS: Set<String> = setOf(
            ToggleSoloModeAction.ACTION_ID,
            ToggleSessionAction.ACTION_ID,
            ToggleEditorAction.ACTION_ID,

            // 保存
            "SaveAll", // 注意：这个不是 IdeActions 里的内置常量，通常仍保留字符串

            // 关闭弹窗、取消操作
            IdeActions.ACTION_EDITOR_ESCAPE,

            // 剪贴板与编辑
            IdeActions.ACTION_COPY,
            IdeActions.ACTION_CUT,
            IdeActions.ACTION_DELETE,
            IdeActions.ACTION_PASTE,
            IdeActions.ACTION_REDO,
            IdeActions.ACTION_SELECT_ALL,
            IdeActions.ACTION_UNDO,

            // 查找
            IdeActions.ACTION_FIND,
            IdeActions.ACTION_FIND_NEXT,
            IdeActions.ACTION_FIND_PREVIOUS,
            IdeActions.ACTION_FIND_IN_PATH,

            // 基础编辑
            IdeActions.ACTION_EDITOR_BACKSPACE,
            IdeActions.ACTION_EDITOR_DELETE,
            IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET,
            IdeActions.ACTION_EDITOR_UNSELECT_WORD_AT_CARET,
            IdeActions.ACTION_EDITOR_TEXT_START,
            IdeActions.ACTION_EDITOR_TEXT_END,
            IdeActions.ACTION_EDITOR_MOVE_LINE_START,
            IdeActions.ACTION_EDITOR_MOVE_LINE_END,
            IdeActions.ACTION_EDITOR_PREVIOUS_WORD,
            IdeActions.ACTION_EDITOR_NEXT_WORD,
            IdeActions.ACTION_EDITOR_DELETE_LINE,
            IdeActions.ACTION_EDITOR_DELETE_TO_WORD_END,
            IdeActions.ACTION_EDITOR_DELETE_TO_WORD_START,
        )
    }

    private val enabled = AtomicBoolean(false)

    fun enable() {
        if (enabled.compareAndSet(false, true)) {
            SoloModeShortcutBlockerAppService.getInstance()
                .registerProject(project)
            println("SoloModeShortcutBlockerService: enabled for project=${project.name}")
        }
    }

    fun disable() {
        if (enabled.compareAndSet(true, false)) {
            SoloModeShortcutBlockerAppService.getInstance()
                .unregisterProject(project)
            println("SoloModeShortcutBlockerService: disabled for project=${project.name}")
        }
    }

    fun isEnabled(): Boolean = enabled.get()

    override fun dispose() {
        SoloModeShortcutBlockerAppService.getInstance()
            .unregisterProject(project)
        println("SoloModeShortcutBlockerService: dispose for project=${project.name}")
    }
}

/* ----------------------------- app service ----------------------------- */

@Service(Service.Level.APP)
private class SoloModeShortcutBlockerAppService : Disposable {

    /** 已启用拦截的 project */
    private val enabledProjects = mutableSetOf<Project>()

    private val dispatcher = IdeEventQueue.EventDispatcher { event ->
        if (enabledProjects.isEmpty()) return@EventDispatcher false

        if (event !is KeyEvent || event.id != KeyEvent.KEY_PRESSED) return@EventDispatcher false

        val project = findProjectForEvent(event) ?: return@EventDispatcher false
        if (project.isDisposed || !enabledProjects.contains(project)) return@EventDispatcher false

        shouldBlock(event)
    }

    init {
        IdeEventQueue.getInstance().addDispatcher(dispatcher, this)
    }

    fun registerProject(project: Project) {
        enabledProjects.add(project)
    }

    fun unregisterProject(project: Project) {
        enabledProjects.remove(project)
    }

    override fun dispose() {
        enabledProjects.clear()
        IdeEventQueue.getInstance().removeDispatcher(dispatcher)
    }

    private fun shouldBlock(event: KeyEvent): Boolean {
        val keymapManager = KeymapManager.getInstance() ?: return false
        val keymap = keymapManager.activeKeymap
        val stroke = KeyStroke.getKeyStrokeForEvent(event) ?: return false

        val actionIds = keymap.getActionIds(stroke)
        if (actionIds.isEmpty()) return false

        for (id in actionIds) {
            if (id in ShortcutBlockerService.WHITELIST_ACTION_IDS) return false
        }

        println("SoloModeShortcutBlockerService: block shortcuts: $stroke")
        return true
    }

    private fun findProjectForEvent(event: KeyEvent): Project? {
        projectFromComponent(event.component)?.let { return it }

        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        return projectFromComponent(focusOwner)
    }

    private fun projectFromComponent(component: Component?): Project? {
        val jc = component as? JComponent ?: return null
        val dataContext = DataManager.getInstance().getDataContext(jc)
        return CommonDataKeys.PROJECT.getData(dataContext)
    }

    companion object {
        fun getInstance(): SoloModeShortcutBlockerAppService {
            return ApplicationManager.getApplication()
                .getService(SoloModeShortcutBlockerAppService::class.java)
        }
    }
}