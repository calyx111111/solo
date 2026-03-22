import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

object EditorTabsRepair {

    fun repair(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            val fem = FileEditorManager.getInstance(project)
            val openFiles = fem.openFiles

            if (openFiles.isNotEmpty()) {
                // 当前 editor 有 tab 页：关闭所有 tab 并按顺序重新打开
                val selectedFiles = fem.selectedFiles
                val selectedFile = selectedFiles.firstOrNull()
                val order = openFiles.toList()

                for (file in order) {
                    fem.closeFile(file)
                }
                for (file in order) {
                    fem.openFile(file, false)
                }
                // 恢复之前选中的文件焦点
                if (selectedFile != null && selectedFile in order) {
                    fem.openFile(selectedFile, true)
                }
            }
        }
    }

}