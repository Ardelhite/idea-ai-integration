package ai.sanakan.fugu.ui

import ai.sanakan.fugu.core.FuguSession
import ai.sanakan.fugu.core.FuguSessionManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener

/**
 * Builds the Karato tool window as a set of chat tabs — one [Content] per
 * [FuguSession] owned by [FuguSessionManager]. Each panel's "+" opens a new tab;
 * closing a tab drops its session. The whole set is persisted, so tabs survive
 * restarts.
 */
class FuguToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val manager = FuguSessionManager.getInstance(project)
        val cm = toolWindow.contentManager
        val factory = ContentFactory.getInstance()

        fun openTab(session: FuguSession, select: Boolean) {
            // Holder so onRename is safe even if invoked during panel construction
            // (before the Content exists); the title falls back to the factory value.
            val contentRef = arrayOfNulls<Content>(1)
            val panel = FuguChatPanel(
                project = project,
                session = session,
                onNewTab = { openTab(manager.create(), true) },
                onRename = { title -> contentRef[0]?.displayName = title },
            )
            val content = factory.createContent(panel, session.title, false)
            contentRef[0] = content
            content.isCloseable = true
            content.setDisposer(panel)
            cm.addContent(content)
            if (select) cm.setSelectedContent(content)
        }

        manager.ensureAtLeastOne()
        manager.sessions.forEach { openTab(it, false) }
        cm.getContent(0)?.let { cm.setSelectedContent(it) }

        cm.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                // Ignore removals during project/tool-window teardown so persisted
                // tabs aren't wiped; only act on a user closing a tab.
                if (project.isDisposed) return
                (event.content.component as? FuguChatPanel)?.let { manager.remove(it.session) }
                if (cm.contentCount == 0) openTab(manager.create(), true)
            }
        })
    }
}
