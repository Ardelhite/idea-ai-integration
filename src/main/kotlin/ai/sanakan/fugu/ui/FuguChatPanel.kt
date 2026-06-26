package ai.sanakan.fugu.ui

import ai.sanakan.fugu.core.ChatMessage
import ai.sanakan.fugu.core.CodexInstaller
import ai.sanakan.fugu.core.FuguSession
import ai.sanakan.fugu.core.ProjectFiles
import ai.sanakan.fugu.settings.FuguSettings
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * The Fugu chat tool-window panel: scrolling transcript on top, a composer with
 * model selector and send/stop controls on the bottom.
 */
class FuguChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, FuguSession.Listener {

    private val session = project.getService(FuguSession::class.java)

    private val transcript = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getListBackground()
    }
    private val scrollPane = JBScrollPane(
        wrapTop(transcript),
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    ).apply { border = JBUI.Borders.empty() }

    private val input = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 3
        emptyText.text = "Ask Fugu to build or change something… (⏎ to send, ⇧⏎ for newline)"
        border = JBUI.Borders.empty(6)
    }
    private val modelCombo = ComboBox(DefaultComboBoxModel(FuguSettings.KNOWN_MODELS.toTypedArray())).apply {
        isEditable = true
        selectedItem = session.model
        toolTipText = "Model"
    }
    private val sendButton = JButton("Send")
    private val statusLabel = JBLabel("Ready").apply {
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(2, 8)
    }

    private val components = HashMap<ChatMessage, MessageComponent>()

    private val setupBanner = buildSetupBanner()
    private val northPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        northPanel.add(buildToolbar())
        northPanel.add(setupBanner)
        add(northPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)

        wireActions()
        session.addListener(this)

        // Rebuild any pre-existing transcript (tool window reopened).
        session.messages.forEach { addMessageComponent(it) }
        updateBusyState(session.isBusy)
        refreshSetupBanner()
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("New Conversation", "Clear the transcript and start a new Fugu thread", AllIcons.General.Add) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = newConversation()
            })
            add(object : AnAction("Stop", "Stop the current turn", AllIcons.Actions.Suspend) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = session.isBusy }
                override fun actionPerformed(e: AnActionEvent) = session.stop()
            })
            addSeparator()
            add(object : AnAction("Install Codex (Fugu)", "Install the Codex CLI wired to Fugu", AllIcons.Actions.Download) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = runInstall()
            })
            add(object : AnAction("Settings", "Open Fugu settings", AllIcons.General.Settings) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = openSettings()
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("FuguChatToolbar", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun buildSetupBanner(): EditorNotificationPanel =
        EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
            text("Codex CLI not found — install it to start using Fugu.")
            createActionLabel("Install Codex (Fugu)") { runInstall() }
            createActionLabel("Get API key") { BrowserUtil.browse(CodexInstaller.CONSOLE_URL) }
            createActionLabel("Settings") { openSettings() }
        }

    private fun refreshSetupBanner() {
        setupBanner.isVisible = !CodexInstaller.isInstalled()
        northPanel.revalidate()
        northPanel.repaint()
    }

    private fun runInstall() {
        val proceed = Messages.showOkCancelDialog(
            project,
            "This runs the Fugu installer:\n\n    ${CodexInstaller.INSTALL_COMMAND}\n\n" +
                "It downloads and executes a setup script from sakana.ai and installs the Codex CLI. " +
                "Make sure SAKANA_API_KEY is set in your environment first " +
                "(create a key at ${CodexInstaller.CONSOLE_URL}).\n\nProceed?",
            "Install Codex (Fugu)",
            "Run Installer",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        if (proceed != Messages.OK) return

        val note = session.systemNote("$ ${CodexInstaller.INSTALL_COMMAND}")
        CodexInstaller.install(project, object : CodexInstaller.Output {
            override fun line(text: String) = onEdt { session.appendSystemNote(note, text) }
            override fun done(exitCode: Int) = onEdt {
                session.appendSystemNote(
                    note,
                    if (exitCode == 0) "✓ Installer finished." else "✗ Installer exited with code $exitCode.",
                )
                if (exitCode == 0 && CodexInstaller.hasFuguLauncher()) {
                    FuguSettings.getInstance().cliPath = "codex-fugu"
                    FuguSettings.getInstance().sakanaProvider = false
                    session.appendSystemNote(note, "Set Codex CLI path to 'codex-fugu'.")
                }
                refreshSetupBanner()
            }

            override fun failed(message: String) = onEdt {
                session.appendSystemNote(note, "✗ Failed to launch installer: $message")
            }
        })
    }

    private fun onEdt(body: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) body() })
    }

    private fun newConversation() {
        session.newConversation()
        components.clear()
        transcript.removeAll()
        transcript.revalidate()
        transcript.repaint()
    }

    private fun wrapTop(inner: JComponent): JComponent {
        // Keeps messages anchored to the top of the viewport.
        val wrapper = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground() }
        wrapper.add(inner, BorderLayout.NORTH)
        return wrapper
    }

    private fun buildComposer(): JComponent {
        val composer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(6),
            )
        }

        val inputScroll = JBScrollPane(input).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            preferredSize = Dimension(0, JBUI.scale(72))
        }
        composer.add(inputScroll, BorderLayout.CENTER)

        val controls = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(6)
            isOpaque = false
        }
        val left = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(JBLabel("Model: ").apply { foreground = JBColor.GRAY })
            add(modelCombo)
        }
        modelCombo.maximumSize = Dimension(JBUI.scale(160), modelCombo.preferredSize.height)
        controls.add(left, BorderLayout.WEST)
        controls.add(sendButton, BorderLayout.EAST)
        composer.add(controls, BorderLayout.SOUTH)

        val south = JPanel(BorderLayout())
        south.add(statusLabel, BorderLayout.NORTH)
        south.add(composer, BorderLayout.CENTER)
        return south
    }

    private fun wireActions() {
        sendButton.addActionListener { onSendOrStop() }

        // Enter sends; Shift+Enter inserts a newline.
        val sendKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        input.inputMap.put(sendKey, "fugu-send")
        input.actionMap.put("fugu-send", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = onSendOrStop()
        })

        modelCombo.addActionListener {
            (modelCombo.editor.item as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { session.model = it }
        }
    }

    private fun onSendOrStop() {
        if (session.isBusy) {
            session.stop()
            return
        }
        val text = input.text
        if (text.isBlank()) return
        (modelCombo.editor.item as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { session.model = it }
        input.text = ""
        session.submit(text)
    }

    // --- FuguSession.Listener (on EDT) ----------------------------------------

    override fun onMessageAdded(message: ChatMessage) {
        addMessageComponent(message)
        scrollToBottom()
    }

    override fun onMessageUpdated(message: ChatMessage) {
        components[message]?.refresh()
        scrollToBottom()
    }

    override fun onTurnStarted() = updateBusyState(true)

    override fun onTurnFinished() = updateBusyState(false)

    override fun onStatus(text: String) {
        statusLabel.text = text
    }

    private fun addMessageComponent(message: ChatMessage) {
        val comp = MessageComponent(message) { call ->
            call.target?.let { ProjectFiles.open(project, it) }
        }
        comp.alignmentX = Component.LEFT_ALIGNMENT
        comp.maximumSize = Dimension(Int.MAX_VALUE, comp.preferredSize.height)
        components[message] = comp
        transcript.add(comp)
        transcript.revalidate()
        transcript.repaint()
    }

    private fun updateBusyState(busy: Boolean) {
        sendButton.text = if (busy) "Stop" else "Send"
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "ai.sanakan.fugu.settings.FuguConfigurable")
    }

    override fun dispose() {
        session.removeListener(this)
    }
}
