package ai.sanakan.fugu.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens the Karato tool window on the right as soon as a project finishes
 * loading, so the agent is visible by default (like Claude-Code GUIs) rather than
 * hidden behind a stripe button the user has to discover.
 */
class KaratoStartupActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) {
            ToolWindowManager.getInstance(project).getToolWindow("Karato")?.activate(null, true)
        }
    }
}
