package ai.sanakan.fugu.ui

import ai.sanakan.fugu.core.ChatMessage
import ai.sanakan.fugu.core.FuguSession
import ai.sanakan.fugu.core.FuguSetup
import ai.sanakan.fugu.core.ProjectFiles
import ai.sanakan.fugu.core.SakanaApi
import com.intellij.openapi.editor.colors.EditorColorsManager
import ai.sanakan.fugu.core.CodexVersion
import ai.sanakan.fugu.core.FuguSessionManager
import ai.sanakan.fugu.core.McpConfig
import ai.sanakan.fugu.settings.FuguPermissionMode
import ai.sanakan.fugu.settings.FuguSecrets
import ai.sanakan.fugu.settings.FuguSettings
import ai.sanakan.fugu.settings.McpMode
import ai.sanakan.fugu.settings.SendShortcut
import com.intellij.openapi.util.SystemInfo
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
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
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The Fugu chat tool-window panel: scrolling transcript on top, a composer with
 * model selector and send/stop controls on the bottom.
 */
class FuguChatPanel(
    private val project: Project,
    val session: FuguSession,
    private val onNewTab: () -> Unit = {},
    private val onRename: (String) -> Unit = {},
) : JPanel(BorderLayout()), Disposable, FuguSession.Listener {

    private val transcript = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = editorBackground()
    }
    private val scrollPane = JBScrollPane(
        wrapTop(transcript),
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    ).apply { border = JBUI.Borders.empty() }

    private val input = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 6
        emptyText.text = "Ask Fugu… (⏎ to send, ⇧⏎ newline, @ to reference a file)"
        border = JBUI.Borders.empty(6)
        background = editorBackground()
    }
    private val fileBar = FileReferenceBar { revalidate() }
    private var inputHeight = JBUI.scale(144)
    private val modelCombo = ComboBox(DefaultComboBoxModel(FuguSettings.KNOWN_MODELS.toTypedArray())).apply {
        isEditable = true
        selectedItem = session.model
        toolTipText = "Model"
    }
    private val modeCombo = ComboBox(DefaultComboBoxModel(FuguPermissionMode.entries.toTypedArray())).apply {
        renderer = comboRenderer { it.modeLabel }
        selectedItem = FuguSettings.getInstance().permissionModeEnum
        toolTipText = FuguSettings.getInstance().permissionModeEnum.display
    }
    private val mcpCombo = ComboBox(DefaultComboBoxModel(McpMode.entries.toTypedArray())).apply {
        renderer = comboRenderer { it.display }
        selectedItem = FuguSettings.getInstance().mcpModeEnum
        toolTipText = "Which Claude MCP servers Codex may use (Off / this project / all)"
    }
    private var syncingMcp = false
    private lateinit var settingsButton: JButton

    private val sendButton = SendButton { onSendOrStop() }
    private val sendHint = JBLabel("", javax.swing.SwingConstants.CENTER).apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(JBUI.scaleFontSize(10f).toFloat())
        alignmentX = Component.CENTER_ALIGNMENT
    }
    private val onSettingsChanged = Runnable {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) { refreshSendHint(); syncMcpCombo() }
        }, ModalityState.any())
    }

    // Spins an ASCII frame beside the tab number while this tab's turn is running.
    private var tabSpinnerFrame = 0
    private val tabSpinnerTimer = Timer(120) {
        tabSpinnerFrame = (tabSpinnerFrame + 1) % TAB_SPINNER.size
        onRename("${session.title} ${TAB_SPINNER[tabSpinnerFrame]}")
    }

    private val statusBar = StatusBar()
    private val promptArea = JPanel(BorderLayout()).apply { isVisible = false }
    private val errorAccordion = ErrorAccordion()
    private val statusLabel = JBLabel("").apply {
        foreground = JBColor.GRAY
        horizontalAlignment = javax.swing.SwingConstants.CENTER
        border = JBUI.Borders.empty(1, 8)
    }

    private val components = HashMap<ChatMessage, MessageComponent>()

    private val setupBanner = buildSetupBanner()
    private val northPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        // Offset the chat from the IDE with a margin + frame so it doesn't visually
        // merge with the editor (both share the editor background colour).
        border = JBUI.Borders.compound(
            JBUI.Borders.empty(5),
            JBUI.Borders.customLine(JBColor.border(), 1),
        )

        northPanel.add(buildToolbar())
        northPanel.add(setupBanner)
        add(northPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)

        wireActions()
        session.addListener(this)
        FuguSettings.addChangeListener(onSettingsChanged)

        // Rebuild any pre-existing transcript (tool window reopened).
        session.messages.forEach { addMessageComponent(it) }
        updateBusyState(session.isBusy)
        errorAccordion.update(session.runtimeErrorLog, session.runtimeErrorCount)
        refreshSetupBanner()
        loadModels()
        checkCodexOutdated()
    }

    /** On startup, if Codex is out of date, tint the gear yellow and prompt an update on hover. */
    private fun checkCodexOutdated() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val info = CodexVersion.check()
            if (!info.outdated) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed || !::settingsButton.isInitialized) return@invokeLater
                settingsButton.icon = TintedIcon(AllIcons.General.Settings, JBColor(0xE5A50A, 0xF2C94C))
                settingsButton.toolTipText =
                    "Codex is outdated (v${info.current} → v${info.latest}). Open Settings → Karato to update."
            }, ModalityState.any())
        }
    }

    private fun buildToolbar(): JComponent {
        // Left: CLEAR (this tab's history) + setup/settings.  Right (opposite the gear):
        // an MCP-scope dropdown, then "+" (new tab) and "×" (close tab).
        val left = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply { isOpaque = false }
        left.add(textButton("CLEAR", "Clear this conversation and start a fresh Fugu thread") { newConversation() })
        left.add(iconButton(AllIcons.Actions.Download, "Set up Fugu") { openSetup() })
        settingsButton = iconButton(AllIcons.General.Settings, "Settings") { openSettings() }
        left.add(settingsButton)

        mcpCombo.maximumSize = Dimension(JBUI.scale(120), mcpCombo.preferredSize.height)
        mcpCombo.addActionListener { onMcpModeSelected() }

        // Right-aligned: MCP scope + new-tab "+".  Closing a tab is the native × on
        // the tab itself (next to its number).
        val right = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(2))).apply { isOpaque = false }
        right.add(mcpCombo)
        right.add(iconButton(AllIcons.General.Add, "New chat tab") { onNewTab() })

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(1, 2)
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
    }

    private fun textButton(text: String, tip: String, action: () -> Unit): JButton = JButton(text).apply {
        toolTipText = tip
        isFocusPainted = false
        margin = JBUI.insets(2, 8)
        font = font.deriveFont(Font.BOLD, JBUI.scaleFontSize(11f).toFloat())
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addActionListener { action() }
    }

    private fun iconButton(icon: javax.swing.Icon, tip: String, action: () -> Unit): JButton = JButton(icon).apply {
        toolTipText = tip
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        margin = JBUI.emptyInsets()
        border = JBUI.Borders.empty(2, 4)
        // Pin to the icon's size so the platform L&F doesn't pad it wide (which would
        // break the tight left/right grouping in the header).
        val d = Dimension(icon.iconWidth + JBUI.scale(8), icon.iconHeight + JBUI.scale(8))
        preferredSize = d
        minimumSize = d
        maximumSize = d
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addActionListener { action() }
    }

    private fun buildSetupBanner(): EditorNotificationPanel =
        EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
            // Hidden until the async setup probe decides — avoids flashing the warning.
            isVisible = false
            text("Fugu isn't set up yet — install Codex, configure the provider, and add your API key.")
            createActionLabel("Set up Fugu") { openSetup() }
            createActionLabel("Get API key") { BrowserUtil.browse(FuguSecrets.CONSOLE_KEYS_URL) }
            createActionLabel("Settings") { openSettings() }
        }

    private fun refreshSetupBanner() {
        // FuguSetup probes PasswordSafe (macOS Keychain) and reads files — both can block
        // for seconds and MUST NOT run on the EDT (they froze the IDE on tool-window open).
        ApplicationManager.getApplication().executeOnPooledThread {
            val codex = FuguSetup.codexInstalled()
            val provider = FuguSetup.providerConfigured()
            val key = FuguSetup.apiKeyPresent()
            val missing = buildList {
                if (!codex) add("Codex CLI")
                if (!provider) add("Sakana provider")
                if (!key) add("API key")
            }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                setupBanner.isVisible = missing.isNotEmpty()
                if (missing.isNotEmpty()) {
                    setupBanner.text("Fugu setup incomplete — missing: ${missing.joinToString(", ")}.")
                }
                northPanel.revalidate()
                northPanel.repaint()
            }, ModalityState.any())
        }
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
        val wrapper = JPanel(BorderLayout()).apply { background = editorBackground() }
        wrapper.add(inner, BorderLayout.NORTH)
        return wrapper
    }

    private fun editorBackground() = EditorColorsManager.getInstance().globalScheme.defaultBackground

    /** Fetches the account's available models from Sakana and fills the model dropdown. */
    private fun loadModels() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = FuguSecrets.getApiKey() ?: return@executeOnPooledThread  // PasswordSafe — off EDT
            val models = SakanaApi.listModels(key).getOrNull()
                ?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                val current = (modelCombo.editor.item as? String)?.takeIf { it.isNotBlank() } ?: session.model
                modelCombo.model = DefaultComboBoxModel(models.toTypedArray())
                modelCombo.selectedItem = if (current in models) current else session.model
            }, ModalityState.any())
        }
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
            preferredSize = Dimension(0, inputHeight)
        }
        // Resize grip (drag to grow/shrink the input) + the file-reference chips.
        val north = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(buildResizeGrip(inputScroll), BorderLayout.NORTH)
            add(fileBar, BorderLayout.CENTER)
        }
        composer.add(north, BorderLayout.NORTH)
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
        val sendBox = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            sendButton.alignmentX = Component.CENTER_ALIGNMENT
            add(sendButton)
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)))
            add(sendHint)
        }
        controls.add(left, BorderLayout.WEST)
        controls.add(sendBox, BorderLayout.EAST)
        composer.add(controls, BorderLayout.SOUTH)
        refreshSendHint()

        val statusArea = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(2)
            add(statusBar, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        // Status, then the runtime-error accordion, then any inline prompt — above the input.
        val northStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(statusArea)
            add(errorAccordion)
            add(promptArea)
        }
        val south = JPanel(BorderLayout())
        south.add(northStack, BorderLayout.NORTH)
        south.add(composer, BorderLayout.CENTER)
        return south
    }

    private fun buildResizeGrip(inputScroll: JComponent): JComponent {
        val grip = object : JComponent() {
            init {
                preferredSize = Dimension(0, JBUI.scale(7))
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR)
            }

            override fun paintComponent(g: java.awt.Graphics) {
                g.color = JBColor.border()
                val w = JBUI.scale(28)
                g.fillRect((width - w) / 2, height / 2, w, JBUI.scale(2))
            }
        }
        val ml = object : java.awt.event.MouseAdapter() {
            private var startY = 0
            private var startH = 0
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                startY = e.yOnScreen
                startH = inputScroll.height
            }

            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                inputHeight = (startH + (startY - e.yOnScreen)).coerceIn(JBUI.scale(56), JBUI.scale(420))
                inputScroll.preferredSize = Dimension(0, inputHeight)
                inputScroll.revalidate()
                this@FuguChatPanel.revalidate()
            }
        }
        grip.addMouseListener(ml)
        grip.addMouseMotionListener(ml)
        return grip
    }

    /** The platform modifier paired with Enter for the "⌘/Alt + Enter" send mode. */
    private val sendModifierMask = if (SystemInfo.isMac) KeyEvent.META_DOWN_MASK else KeyEvent.ALT_DOWN_MASK

    private fun wireActions() {
        // Enter handling depends on the configured send shortcut, read live so a change
        // in Settings applies immediately. A *consuming* key listener runs before Swing's
        // own Enter binding (insert-break / our action map), so it fully owns the keystroke
        // and there is no double-handling:
        //   ENTER mode:    Enter sends      · ⇧Enter and ⌘/Alt+Enter behave as below
        //   MODIFIER mode: ⌘/Alt+Enter sends · plain Enter inserts a newline
        // In every mode ⇧Enter inserts a newline and ⌘/Alt+Enter sends.
        input.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                val withSendModifier = (e.modifiersEx and sendModifierMask) != 0
                val shift = (e.modifiersEx and KeyEvent.SHIFT_DOWN_MASK) != 0
                val send = when {
                    shift -> false
                    withSendModifier -> true
                    else -> FuguSettings.getInstance().sendShortcutEnum == SendShortcut.ENTER
                }
                e.consume()
                if (send) onSendOrStop() else input.replaceSelection("\n")
            }
        })

        // "@" opens a project-file picker; the chosen file becomes a reference chip.
        input.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) {
                if (e.length == 1 && runCatching { input.document.getText(e.offset, 1) }.getOrNull() == "@") {
                    SwingUtilities.invokeLater { showFilePicker(e.offset) }
                }
            }

            override fun removeUpdate(e: javax.swing.event.DocumentEvent) {}
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) {}
        })

        // Drag & drop files onto the input to attach them.
        input.dropTarget = java.awt.dnd.DropTarget(input, object : java.awt.dnd.DropTargetAdapter() {
            override fun drop(e: java.awt.dnd.DropTargetDropEvent) {
                e.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                @Suppress("UNCHECKED_CAST")
                val list = runCatching {
                    e.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<java.io.File>
                }.getOrNull().orEmpty()
                list.forEach { f ->
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)?.let { fileBar.addFile(it) }
                }
                e.dropComplete(true)
            }
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
        val refs = fileBar.files()
        if (text.isBlank() && refs.isEmpty()) return
        (modelCombo.editor.item as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { session.model = it }
        val prompt = buildPrompt(text, refs)
        input.text = ""
        fileBar.clear()
        session.submit(prompt)
    }

    /** Appends `@<absolute-path>` mentions for any attached files to the prompt. */
    private fun buildPrompt(text: String, refs: List<VirtualFile>): String {
        if (refs.isEmpty()) return text
        val mentions = refs.joinToString("\n") { "@${it.path}" }
        return if (text.isBlank()) "Referenced files:\n$mentions" else "$text\n\nReferenced files:\n$mentions"
    }

    private fun showFilePicker(atOffset: Int) {
        // Enumerating project content is a slow op — do it off the EDT, then pop up.
        ApplicationManager.getApplication().executeOnPooledThread {
            val files = searchProjectFiles()
            ApplicationManager.getApplication().invokeLater({
                if (files.isNotEmpty() && !project.isDisposed) showFilePopup(atOffset, files)
            }, ModalityState.any())
        }
    }

    private fun showFilePopup(atOffset: Int, files: List<VirtualFile>) {
        val base = project.basePath?.trimEnd('/')
        val step = object : BaseListPopupStep<VirtualFile>("Reference a file", files) {
            override fun isSpeedSearchEnabled() = true
            override fun getIconFor(value: VirtualFile) = value.fileType.icon
            override fun getTextFor(value: VirtualFile): String {
                val parent = value.parent?.path ?: ""
                val rel = if (base != null && parent.startsWith(base)) parent.removePrefix(base).trimStart('/') else parent
                return if (rel.isEmpty()) value.name else "${value.name}   $rel/"
            }

            override fun onChosen(selectedValue: VirtualFile, finalChoice: Boolean): PopupStep<*>? {
                runCatching {
                    if (atOffset < input.document.length && input.document.getText(atOffset, 1) == "@") {
                        input.document.remove(atOffset, 1)
                    }
                }
                fileBar.addFile(selectedValue)
                return FINAL_CHOICE
            }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        val rect = runCatching { input.modelToView2D(atOffset) }.getOrNull()
        if (rect != null) {
            popup.show(RelativePoint(input, java.awt.Point(rect.x.toInt(), (rect.y + rect.height).toInt())))
        } else {
            popup.showUnderneathOf(input)
        }
    }

    private fun searchProjectFiles(): List<VirtualFile> {
        val result = ArrayList<VirtualFile>()
        ReadAction.run<RuntimeException> {
            val fileIndex = ProjectFileIndex.getInstance(project)
            val statusManager = FileStatusManager.getInstance(project)
            // Skip directories that are project-excluded or VCS-ignored (e.g. node_modules,
            // build output, or a gitignored postgres data volume like db/data/) so the @
            // picker isn't flooded with thousands of irrelevant files.
            fun skip(f: VirtualFile): Boolean =
                fileIndex.isExcluded(f) || statusManager.getStatus(f) == FileStatus.IGNORED

            val stack = ArrayDeque<VirtualFile>()
            ProjectRootManager.getInstance(project).contentRoots.forEach { stack.addLast(it) }
            while (stack.isNotEmpty() && result.size < 500) {
                val children = stack.removeLast().children ?: continue
                for (child in children) {
                    if (result.size >= 500) break
                    if (child.name == ".git" || skip(child)) continue
                    if (child.isDirectory) stack.addLast(child) else result.add(child)
                }
            }
        }
        return result.sortedBy { it.name }
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

    override fun onRuntimeError(log: String, count: Int) {
        errorAccordion.update(log, count)
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
        showInPrompt(form)
    }

    override fun onApprovalPrompt(
        request: ai.sanakan.fugu.cli.ApprovalRequest,
        respond: (ai.sanakan.fugu.cli.ApprovalDecision) -> Unit,
    ) {
        showInPrompt(ApprovalPanel(request) { decision -> clearPrompt(); respond(decision) })
    }

    private fun showInPrompt(form: JComponent) {
        promptArea.removeAll()
        promptArea.add(form, BorderLayout.CENTER)
        promptArea.isVisible = true
        promptArea.revalidate()
        promptArea.repaint()
        scrollToBottom()
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
        sendButton.running = busy
        statusBar.running = busy
        if (busy) {
            if (!tabSpinnerTimer.isRunning) { tabSpinnerFrame = 0; tabSpinnerTimer.start() }
            onRename("${session.title} ${TAB_SPINNER[0]}")
        } else {
            tabSpinnerTimer.stop()
            onRename(session.title)
        }
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun openSettings() {
        // Navigate to our configurable by class so it reliably opens Tools → Karato
        // (the id-string overload sometimes lands on the settings root).
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ai.sanakan.fugu.settings.FuguConfigurable::class.java)
    }

    /** Applies a new MCP scope: persist, sync other tabs, rewrite Codex config, reload. */
    private fun onMcpModeSelected() {
        if (syncingMcp) return
        val mode = mcpCombo.selectedItem as? McpMode ?: return
        val settings = FuguSettings.getInstance()
        if (mode.name == settings.mcpMode) return
        settings.mcpMode = mode.name
        FuguSettings.fireChanged()
        statusLabel.text = "MCP: applying ${mode.display.removePrefix("MCP: ")}…"
        ApplicationManager.getApplication().executeOnPooledThread {
            McpConfig.apply(project, mode)
            val names = McpConfig.collect(project, mode).map { it.name }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                FuguSessionManager.getInstance(project).reloadAllTransports()
                statusLabel.text = if (names.isEmpty()) "MCP disabled" else "MCP ready: ${names.joinToString(", ")}"
            }, ModalityState.any())
        }
    }

    private fun syncMcpCombo() {
        val mode = FuguSettings.getInstance().mcpModeEnum
        if (mcpCombo.selectedItem == mode) return
        syncingMcp = true
        mcpCombo.selectedItem = mode
        syncingMcp = false
    }

    /** Shows the active send keystroke under the Send button (and in the input hint). */
    private fun refreshSendHint() {
        val hint = when (FuguSettings.getInstance().sendShortcutEnum) {
            SendShortcut.ENTER -> "Enter"
            SendShortcut.MODIFIER_ENTER -> if (SystemInfo.isMac) "⌘ + Enter" else "Alt + Enter"
        }
        sendHint.text = hint
        input.emptyText.text = "Ask Fugu… ($hint to send, @ to reference a file)"
        sendHint.revalidate()
        sendHint.repaint()
    }

    override fun dispose() {
        session.removeListener(this)
        FuguSettings.removeChangeListener(onSettingsChanged)
        tabSpinnerTimer.stop()
        statusBar.stop()
    }

    /**
     * A collapsible "! Runtime Exception !" panel that accumulates this session's runtime
     * errors (verbatim, indentation preserved). Hidden until the first error; cleared with
     * the session (tab close) or New Conversation.
     */
    private inner class ErrorAccordion : JPanel(BorderLayout()) {
        private val warn = JBColor(0xD64545, 0xF87171)
        private var expanded = false
        private val chevron = JBLabel(AllIcons.General.ChevronRight)
        private val titleLabel = JBLabel("! Runtime Exception !").apply { foreground = warn; font = font.deriveFont(Font.BOLD) }
        private val body = JBTextArea().apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = false
            font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
            border = JBUI.Borders.empty(4, 22, 4, 6)
        }
        private val bodyScroll = JBScrollPane(body).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(0, JBUI.scale(160))
            isVisible = false
        }

        init {
            isVisible = false
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLine(warn, 1), JBUI.Borders.empty(2))
            val header = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(1))).apply {
                isOpaque = false
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                add(chevron)
                add(titleLabel)
            }
            header.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = setExpanded(!expanded)
            })
            add(header, BorderLayout.NORTH)
            add(bodyScroll, BorderLayout.CENTER)
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

        private fun setExpanded(value: Boolean) {
            expanded = value
            bodyScroll.isVisible = value
            chevron.icon = if (value) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
            revalidate(); repaint()
        }

        fun update(log: String, count: Int) {
            if (log.isBlank()) { isVisible = false; return }
            titleLabel.text = "! Runtime Exception !  ($count)"
            body.text = log
            body.caretPosition = body.document.length // keep the newest error in view
            isVisible = true
            revalidate(); repaint()
        }
    }

    private companion object {
        /**
         * Spinner frames shown next to a running tab's number. Braille cells all
         * have the same advance width, so the tab width stays fixed as it spins
         * (unlike | / - \, which jitter in a proportional font).
         */
        val TAB_SPINNER = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    }
}

/** A combo-box renderer via subclassing (the SimpleListCellRenderer.create factory is
 *  scheduled for removal). */
private fun <T> comboRenderer(render: (T) -> String) = object : SimpleListCellRenderer<T>() {
    override fun customize(list: javax.swing.JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
        text = value?.let(render) ?: ""
    }
}

/**
 * Tints an icon by filling its opaque pixels with [tint] (SRC_ATOP). Pure Swing, so it
 * avoids IconUtil.colorize — whose default-args bridge was removed in 2025.2.
 */
private class TintedIcon(private val base: javax.swing.Icon, private val tint: java.awt.Color) : javax.swing.Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            base.paintIcon(c, g2, x, y)
            g2.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_ATOP)
            g2.color = tint
            g2.fillRect(x, y, iconWidth, iconHeight)
        } finally {
            g2.dispose()
        }
    }

    override fun getIconWidth(): Int = base.iconWidth
    override fun getIconHeight(): Int = base.iconHeight
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
