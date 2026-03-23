package com.example.solo

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class EditorTabsRepairService(private val project: Project) : Disposable {

    private val receivedAnyEvent = AtomicBoolean(false)
    private var messageBusConnection: MessageBusConnection? = null

    init {
        messageBusConnection = project.messageBus.connect(this).also { conn ->
            conn.subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                    override fun fileOpened(
                        source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile
                    ) = onEventReceived()

                    override fun fileClosed(
                        source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile
                    ) = onEventReceived()

                    override fun selectionChanged(event: FileEditorManagerEvent) = onEventReceived()
                })
        }
    }

    private fun onEventReceived() {

        if (receivedAnyEvent.compareAndSet(false, true)) {

            unregisterCallback()
        }
    }

    private fun unregisterCallback() {
        messageBusConnection?.disconnect()
        messageBusConnection = null
    }

    fun repair() {
        if (receivedAnyEvent.get()) {
            return
        }

        ApplicationManager.getApplication().invokeLater {

            if (receivedAnyEvent.get()) {
                return@invokeLater
            }

            val fem = FileEditorManager.getInstance(project)
            val openFiles = fem.openFiles

            if (openFiles.isEmpty()) {
                unregisterCallback()
                return@invokeLater
            }

            val selectedFile = fem.selectedFiles.firstOrNull() ?: openFiles.first()
            fem.closeFile(selectedFile)

            ApplicationManager.getApplication().invokeLater {
                fem.openFile(selectedFile, true)
                unregisterCallback()
            }
        }
    }

    override fun dispose() {
        unregisterCallback()
    }
}