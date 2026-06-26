package ai.sanakan.fugu.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Opens the Karato tool window on the right as soon as a project finishes
 * loading, so the agent is visible by default (like Claude-Code GUIs) rather than
 * hidden behind a stripe button the user has to discover.
 */
@Suppress("DEPRECATION", "UnstableApiUsage")
class KaratoStartupActivity : StartupActivity, DumbAware {
    override fun runActivity(project: Project) {
        val manager = ToolWindowManager.getInstance(project)
        manager.invokeLater {
            manager.getToolWindow("Karato")?.activate(null, true)
        }
    }
}
