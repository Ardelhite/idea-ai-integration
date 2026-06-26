package ai.sanakan.fugu.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/** Helpers for mapping agent file paths back onto IDE files. */
object ProjectFiles {

    /** Resolves a path (absolute, or relative to the project root) to a VFS file. */
    fun resolve(project: Project, path: String): VirtualFile? {
        val ioFile = File(path).let { if (it.isAbsolute) it else File(project.basePath ?: "", path) }
        val fs = LocalFileSystem.getInstance()
        return fs.refreshAndFindFileByIoFile(ioFile)
    }

    /** Opens the given path in the editor, refreshing the VFS first. */
    fun open(project: Project, path: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val vf = resolve(project, path) ?: return@invokeLater
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }
}
