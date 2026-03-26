package com.huawei.agenticmode.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 共享的 FileEditorManagerListener 分发器。
 * 仅订阅一次 FILE_EDITOR_MANAGER，将 tab 变化事件分发给多个回调，供 EditorTabsRepairService、
 * EmptyEditorHeaderService、EditorTabsToolbarService、EditorTabPopupService、EditorTabDragLockService 等复用。
 */
@Service(Service.Level.PROJECT)
class FileEditorChangeDispatcher(private val project: Project) : Disposable {

    private val callbacks = CopyOnWriteArrayList<() -> Unit>()
    private var messageBusConnection: MessageBusConnection? = null

    private val listener = object : FileEditorManagerListener {
        override fun fileOpened(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
            fireCallbacks()
        }

        override fun fileClosed(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
            fireCallbacks()
        }

        override fun selectionChanged(event: FileEditorManagerEvent) {
            fireCallbacks()
        }
    }

    init {
        messageBusConnection = project.messageBus.connect(this).also { conn ->
            conn.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, listener)
        }
    }

    /** 注册回调，每次 tab 变化时调用 */
    fun addCallback(callback: () -> Unit) {
        callbacks.add(callback)
    }

    /** 移除回调 */
    fun removeCallback(callback: () -> Unit) {
        callbacks.remove(callback)
    }

    private fun fireCallbacks() {
        callbacks.forEach { it.invoke() }
    }

    override fun dispose() {
        callbacks.clear()
        messageBusConnection?.disconnect()
        messageBusConnection = null
    }
}
