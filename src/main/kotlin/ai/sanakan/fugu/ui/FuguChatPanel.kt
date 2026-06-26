package ai.sanakan.fugu.ui

import ai.sanakan.fugu.core.ChatMessage
import ai.sanakan.fugu.core.FuguSession
import ai.sanakan.fugu.core.FuguSetup
import ai.sanakan.fugu.core.ProjectFiles
import ai.sanakan.fugu.settings.FuguPermissionMode
import ai.sanakan.fugu.settings.FuguSecrets
import ai.sanakan.fugu.settings.FuguSettings
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

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
    private val modeCombo = ComboBox(DefaultComboBoxModel(FuguPermissionMode.entries.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { it.modeLabel }
        selectedItem = FuguSettings.getInstance().permissionModeEnum
        toolTipText = FuguSettings.getInstance().permissionModeEnum.display
    }
    private val sendButton = JButton("Send")
    private val statusBar = StatusBar()
    private val promptArea = JPanel(BorderLayout()).apply { isVisible = false }
    private val statusLabel = JBLabel("").apply {
        foreground = JBColor.GRAY
        horizontalAlignment = javax.swing.SwingConstants.CENTER
        border = JBUI.Borders.empty(1, 8)
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
            add(object : AnAction("Set up Fugu", "Install Codex, configure the provider, and set the API key", AllIcons.Actions.Download) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = openSetup()
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
            text("Fugu isn't set up yet — install Codex, configure the provider, and add your API key.")
            createActionLabel("Set up Fugu") { openSetup() }
            createActionLabel("Get API key") { BrowserUtil.browse(FuguSecrets.CONSOLE_KEYS_URL) }
            createActionLabel("Settings") { openSettings() }
        }

    private fun refreshSetupBanner() {
        setupBanner.isVisible = !FuguSetup.isReady()
        setupBanner.text(setupBannerText())
        northPanel.revalidate()
        northPanel.repaint()
    }

    private fun setupBannerText(): String {
        val missing = buildList {
            if (!FuguSetup.codexInstalled()) add("Codex CLI")
            if (!FuguSetup.providerConfigured()) add("Sakana provider")
            if (!FuguSetup.apiKeyPresent()) add("API key")
        }
        return "Fugu setup incomplete — missing: ${missing.joinToString(", ")}."
    }

    private fun openSetup() {
        FuguSetupDialog(project).show()
        refreshSetupBanner()
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
            add(javax.swing.Box.createHorizontalStrut(JBUI.scale(10)))
            add(JBLabel("Mode: ").apply { foreground = JBColor.GRAY })
            add(modeCombo)
        }
        modelCombo.maximumSize = Dimension(JBUI.scale(150), modelCombo.preferredSize.height)
        modeCombo.maximumSize = Dimension(JBUI.scale(110), modeCombo.preferredSize.height)
        controls.add(left, BorderLayout.WEST)
        controls.add(sendButton, BorderLayout.EAST)
        composer.add(controls, BorderLayout.SOUTH)

        val statusArea = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(2)
            add(statusBar, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        // Inline prompt sits between the status and the composer input.
        val northStack = JPanel(BorderLayout()).apply {
            add(statusArea, BorderLayout.NORTH)
            add(promptArea, BorderLayout.CENTER)
        }
        val south = JPanel(BorderLayout())
        south.add(northStack, BorderLayout.NORTH)
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

        modeCombo.addActionListener {
            (modeCombo.selectedItem as? FuguPermissionMode)?.let { mode ->
                FuguSettings.getInstance().permissionMode = mode.name
                modeCombo.toolTipText = mode.display
                statusLabel.text = "Mode: ${mode.modeLabel} — ${mode.display}"
            }
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

    override fun onUserPrompt(
        prompt: ai.sanakan.fugu.cli.UserPrompt,
        respond: (ai.sanakan.fugu.cli.PromptAction, Map<String, List<String>>) -> Unit,
    ) {
        val form = UserPromptPanel(
            prompt,
            onSubmit = { answers -> clearPrompt(); respond(ai.sanakan.fugu.cli.PromptAction.ACCEPT, answers) },
            onCancel = {
                clearPrompt()
                val action = if (prompt.kind == ai.sanakan.fugu.cli.UserPromptKind.ELICITATION) {
                    ai.sanakan.fugu.cli.PromptAction.CANCEL
                } else {
                    ai.sanakan.fugu.cli.PromptAction.DECLINE
                }
                respond(action, emptyMap())
            },
        )
        promptArea.removeAll()
        promptArea.add(form, BorderLayout.CENTER)
        promptArea.isVisible = true
        promptArea.revalidate()
        promptArea.repaint()
    }

    private fun clearPrompt() {
        promptArea.removeAll()
        promptArea.isVisible = false
        promptArea.revalidate()
        promptArea.repaint()
    }

    private fun addMessageComponent(message: ChatMessage) {
        val comp = MessageComponent(message, project.basePath) { call ->
            call.target?.let { ProjectFiles.open(project, it) }
        }
        comp.alignmentX = Component.LEFT_ALIGNMENT
        components[message] = comp
        transcript.add(comp)
        transcript.revalidate()
        transcript.repaint()
    }

    private fun updateBusyState(busy: Boolean) {
        sendButton.text = if (busy) "Stop" else "Send"
        statusBar.running = busy
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
        statusBar.stop()
    }
}

/**
 * A big, centered, ASCII-art status line: `---- READY ----` in blue when idle, and
 * an animated `>--- RUNNING >---` in green while a turn runs (the `>` marches right
 * once per second).
 */
private class StatusBar : JComponent() {
    private val readyColor = JBColor(0x2563EB, 0x6AA0FF)
    private val runColor = JBColor(0x1A9E5E, 0x4ADE80)
    private var frame = 0
    private val timer = Timer(1000) { frame = (frame + 1) % 4; repaint() }

    var running: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            frame = 0
            if (value && isShowing) timer.start() else timer.stop()
            repaint()
        }

    init {
        font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scaleFontSize(15f))
        preferredSize = Dimension(0, JBUI.scale(26))
    }

    fun stop() = timer.stop()

    override fun addNotify() {
        super.addNotify()
        if (running) timer.start()
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font
            g2.color = if (running) runColor else readyColor
            val text = if (running) runningText() else "---- READY ----"
            val fm = g2.fontMetrics
            val x = (width - fm.stringWidth(text)) / 2
            val y = (height - fm.height) / 2 + fm.ascent
            g2.drawString(text, x.coerceAtLeast(0), y)
        } finally {
            g2.dispose()
        }
    }

    private fun runningText(): String {
        val bar = String(CharArray(4) { if (it == frame) '>' else '-' })
        return "$bar RUNNING $bar"
    }
}
