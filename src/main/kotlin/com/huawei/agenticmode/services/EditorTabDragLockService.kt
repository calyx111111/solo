package com.huawei.agenticmode.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.tabs.impl.JBTabsImpl
import java.awt.Component
import java.awt.Container
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Solo 模式下关闭编辑器 Tab 拖拽；通过 [enable]/[disable] 控制。
 * Tab 数量变化依赖 [FileEditorChangeDispatcher]（与 [EditorTabPopupService] 等服务相同），不在此复用其逻辑。
 */
@Service(Service.Level.PROJECT)
class EditorTabDragLockService(
    private val project: Project
) : Disposable {

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

            val callback: () -> Unit = { refreshDragLockWhenEnabled() }
            tabChangeCallback = callback
            project.service<FileEditorChangeDispatcher>().addCallback(callback)
        } else {
            rebindRootIfNeeded()
        }
    }

    fun enable() {
        if (!installed || project.isDisposed) return
        enabled.set(true)
        refreshDragLockWhenEnabled()
    }

    fun disable() {
        if (!installed || project.isDisposed) return
        enabled.set(false)
        rebindRootAndApplyDragging(dragEnabled = true)
    }

    fun isEnabled(): Boolean = enabled.get()

    override fun dispose() {
        installed = false
        enabled.set(false)

        tabChangeCallback?.let { project.service<FileEditorChangeDispatcher>().removeCallback(it) }
        tabChangeCallback = null

        applyTabDraggingUnder(currentRoot ?: safeGetRootContainer(), dragEnabled = true)

        currentRoot = null
        rootProvider = null
    }

    private fun refreshDragLockWhenEnabled() {
        if (!enabled.get()) return
        rebindRootAndApplyDragging(dragEnabled = false)
    }

    private fun rebindRootAndApplyDragging(dragEnabled: Boolean) {
        val root = currentRoot ?: return
        applyTabDraggingUnder(root, dragEnabled)
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

    private fun applyTabDraggingUnder(root: Component?, dragEnabled: Boolean) {
        if (root == null) return
        traverse(root) { c ->
            if (c is JBTabsImpl) {
                try {
                    c.setTabDraggingEnabled(dragEnabled)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun traverse(component: Component, visit: (Component) -> Unit) {
        visit(component)
        if (component is Container) {
            component.components.forEach { traverse(it, visit) }
        }
    }
}
