package ai.sanakan.fugu.core

import ai.sanakan.fugu.cli.ApprovalDecision
import ai.sanakan.fugu.cli.ApprovalRequest
import ai.sanakan.fugu.cli.FuguAgentListener
import ai.sanakan.fugu.cli.FuguAppServerClient
import ai.sanakan.fugu.cli.FuguCliClient
import ai.sanakan.fugu.cli.FuguEvent
import ai.sanakan.fugu.cli.FuguTransport
import ai.sanakan.fugu.cli.PromptAction
import ai.sanakan.fugu.cli.UserPrompt
import ai.sanakan.fugu.settings.FuguSettings
import ai.sanakan.fugu.settings.FuguTransportKind
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One chat tab: bridges the Codex subprocess and the UI and holds the transcript
 * + Codex thread id. Instances are owned by [FuguSessionManager], which persists
 * them all into the project's workspace file so tabs survive restarts.
 *
 * One assistant bubble is built per turn; agent messages append text and tool
 * items appear as cards on that bubble. All listener callbacks are dispatched on
 * the EDT so the chat panel can mutate Swing state directly.
 */
class FuguSession(private val project: Project) : Disposable, FuguAgentListener {

    interface Listener {
        fun onMessageAdded(message: ChatMessage)
        fun onMessageUpdated(message: ChatMessage)
        fun onTurnStarted()
        fun onTurnFinished()
        fun onStatus(text: String)

        /** The agent wants a structured answer; render it inline and call [respond] once. */
        fun onUserPrompt(prompt: UserPrompt, respond: (PromptAction, Map<String, List<String>>) -> Unit) {}

        /** The agent wants approval before acting; render it inline and call [respond] once. */
        fun onApprovalPrompt(request: ApprovalRequest, respond: (ApprovalDecision) -> Unit) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    val messages = mutableListOf<ChatMessage>()

    /** Tab label; a sequential number assigned by [FuguSessionManager]. */
    var title: String = "1"
        private set

    /** Selected model for this session; defaults from settings, changeable in UI. */
    var model: String = FuguSettings.getInstance().model

    private var currentAssistant: ChatMessage? = null
    private var turnActive = false

    /** Project agent files (CLAUDE.md etc.) are injected once per Codex thread. */
    private var agentContextSent = false

    /** Set when an MCP-mode change arrives mid-turn; the transport reloads after. */
    private var pendingReload = false

    /** Item id of the message currently being streamed, to detect message boundaries. */
    private var streamingItemId: String? = null
    private var streamingTextBlock: TextBlock? = null

    /** Cumulative output tokens this session (Sakana exposes no account-level usage API). */
    private var sessionOutputTokens = 0L

    // Created on first use only, so persisting untouched tabs never spawns a transport.
    private var clientRef: FuguTransport? = null
    private var pendingThreadId: String? = null
    private val client: FuguTransport
        get() {
            clientRef?.let { return it }
            // Mirror the user's Claude MCP servers into Codex before it launches.
            McpConfig.apply(project, FuguSettings.getInstance().mcpModeEnum)
            val dir = project.basePath ?: System.getProperty("user.dir")
            val created = when (FuguSettings.getInstance().transportKind) {
                FuguTransportKind.EXEC -> FuguCliClient(dir, this)
                FuguTransportKind.APP_SERVER -> FuguAppServerClient(dir, this)
            }
            pendingThreadId?.let { created.restoreThread(it) }
            clientRef = created
            return created
        }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** Sets the tab label without deriving from a prompt (used for new empty tabs). */
    fun assignTitle(newTitle: String) { title = newTitle }

    val isBusy: Boolean get() = turnActive

    /** Submit a user turn. */
    fun submit(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || turnActive) return

        val userMsg = ChatMessage(ChatRole.USER, trimmed)
        messages.add(userMsg)
        notify { it.onMessageAdded(userMsg) }

        turnActive = true
        currentAssistant = null
        notify { it.onTurnStarted() }
        notify { it.onStatus("Fugu is working…") }

        val injectContext = !agentContextSent && FuguSettings.getInstance().loadAgentContext
        if (injectContext) agentContextSent = true

        // Process launch + file reads can block; keep them off the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val prompt = if (injectContext) {
                val ctx = AgentContext.collect(project)
                if (ctx.files.isNotEmpty()) {
                    onEdt { notify { it.onStatus("Loaded ${ctx.files.size} project agent file(s): ${ctx.files.joinToString(", ")}") } }
                }
                if (ctx.text.isBlank()) trimmed else "${ctx.text}\n\n----\n\n$trimmed"
            } else {
                trimmed
            }
            client.send(prompt, model)
        }
    }

    fun stop() {
        clientRef?.stop()
        finishTurn()
    }

    /** Recreates the transport on the next turn (e.g. after an MCP-mode change). */
    fun reloadTransport() {
        if (turnActive) { pendingReload = true; return }
        clientRef?.stop()
        clientRef = null
    }

    /** Clears the transcript and starts a fresh Codex thread. */
    fun newConversation() {
        clientRef?.reset()
        pendingThreadId = null
        messages.clear()
        currentAssistant = null
        turnActive = false
        streamingItemId = null
        streamingTextBlock = null
        sessionOutputTokens = 0L
        agentContextSent = false
        notify { it.onStatus("New conversation") }
    }

    // --- FuguCliClient.Listener (called from process reader threads) -----------

    override fun onEvent(event: FuguEvent) = onEdt {
        when (event) {
            is FuguEvent.Init ->
                notify { it.onStatus("Session ready (${event.model ?: model})") }

            is FuguEvent.AgentMessage -> {
                val msg = ensureAssistant()
                msg.addText(event.text)      // a finalized message becomes its own block
                streamingTextBlock = null
                streamingItemId = null
                notify { it.onMessageUpdated(msg) }
            }

            is FuguEvent.AgentMessageDelta -> {
                val msg = ensureAssistant()
                // A new message id (or text after a tool) starts a fresh text block.
                if (streamingItemId != event.itemId || streamingTextBlock == null) {
                    val block = TextBlock()
                    msg.blocks.add(block)
                    streamingTextBlock = block
                    streamingItemId = event.itemId
                }
                streamingTextBlock?.text?.append(event.text)
                notify { it.onMessageUpdated(msg) }
            }

            is FuguEvent.ToolStarted -> {
                val msg = ensureAssistant()
                if (msg.findTool(event.id) == null) {
                    msg.addTool(ToolCall(event.id, event.name, event.input))
                    streamingTextBlock = null     // next prose goes after this tool card
                    streamingItemId = null
                    notify { it.onMessageUpdated(msg) }
                }
            }

            is FuguEvent.ToolCompleted -> {
                val msg = ensureAssistant()
                val existing = msg.findTool(event.id)
                if (existing != null) {
                    existing.result = event.result
                    existing.isError = event.isError
                } else {
                    msg.addTool(ToolCall(event.id, event.name, event.input, event.result, event.isError))
                }
                streamingTextBlock = null
                streamingItemId = null
                notify { it.onMessageUpdated(msg) }
                refreshProjectFiles()
            }

            is FuguEvent.Result -> {
                if (event.isError && event.text != null) {
                    val err = ChatMessage(ChatRole.ERROR, event.text)
                    messages.add(err)
                    notify { it.onMessageAdded(err) }
                }
                finishTurn()
                event.outputTokens?.let { tok ->
                    sessionOutputTokens += tok
                    notify { it.onStatus("Done · $tok output tokens (session total: $sessionOutputTokens)") }
                }
                refreshProjectFiles()
            }

            is FuguEvent.Other -> Unit
            is FuguEvent.Raw -> Unit
        }
    }

    override fun onStderr(line: String) = onEdt {
        // Codex emits ANSI-coloured log lines on stderr; strip the escape codes so the
        // status line stays readable (otherwise "[2m…[31mERROR[0m…" leaks through).
        val clean = line.replace(ANSI_ESCAPE, "").trim()
        if (clean.isNotEmpty()) notify { it.onStatus(clean) }
    }

    override fun onProcessTerminated(exitCode: Int) = onEdt {
        if (turnActive) {
            if (exitCode != 0) {
                val err = ChatMessage(ChatRole.ERROR, "Codex exited with code $exitCode.")
                messages.add(err)
                notify { it.onMessageAdded(err) }
            }
            finishTurn()
        }
    }

    override fun onStartFailed(message: String) = onEdt {
        val err = ChatMessage(ChatRole.ERROR, message)
        messages.add(err)
        notify { it.onMessageAdded(err) }
        finishTurn()
    }

    override fun onApproval(request: ApprovalRequest, respond: (ApprovalDecision) -> Unit) = onEdt {
        if (listeners.isEmpty()) {
            respond(ApprovalDecision.DECLINE)
            return@onEdt
        }
        val once = AtomicBoolean(false)
        val guarded: (ApprovalDecision) -> Unit = { decision ->
            if (once.compareAndSet(false, true)) {
                notify { it.onStatus("Approval: ${decision.wire}") }
                respond(decision)
            }
        }
        notify { it.onApprovalPrompt(request, guarded) }
    }

    override fun onUserInput(prompt: UserPrompt, respond: (PromptAction, Map<String, List<String>>) -> Unit) = onEdt {
        if (listeners.isEmpty()) {
            respond(PromptAction.DECLINE, emptyMap())
            return@onEdt
        }
        // Surface inline in the panel; ensure exactly one response reaches the transport.
        val once = AtomicBoolean(false)
        val guarded: (PromptAction, Map<String, List<String>>) -> Unit = { action, answers ->
            if (once.compareAndSet(false, true)) respond(action, answers)
        }
        notify { it.onUserPrompt(prompt, guarded) }
    }

    // --- internals -------------------------------------------------------------

    private fun ensureAssistant(): ChatMessage {
        currentAssistant?.let { return it }
        val msg = ChatMessage(ChatRole.ASSISTANT).apply { streaming = true; model = this@FuguSession.model }
        currentAssistant = msg
        streamingItemId = null
        streamingTextBlock = null
        messages.add(msg)
        notify { it.onMessageAdded(msg) }
        return msg
    }

    private fun finishTurn() {
        if (!turnActive) return
        turnActive = false
        currentAssistant?.streaming = false
        currentAssistant = null
        streamingItemId = null
        streamingTextBlock = null
        if (pendingReload) {
            pendingReload = false
            clientRef?.stop()
            clientRef = null
        }
        notify { it.onTurnFinished() }
        notify { it.onStatus("Ready") }
    }

    /** Adds a system note to the transcript (setup/install output). Call on the EDT. */
    fun systemNote(text: String): ChatMessage {
        val msg = ChatMessage(ChatRole.SYSTEM, text)
        messages.add(msg)
        notify { it.onMessageAdded(msg) }
        return msg
    }

    /** Appends a line to an existing system note. Call on the EDT. */
    fun appendSystemNote(msg: ChatMessage, line: String) {
        if (!msg.isEmpty) msg.appendToLastText("\n")
        msg.appendToLastText(line)
        notify { it.onMessageUpdated(msg) }
    }

    // --- persistence (driven by FuguSessionManager) ----------------------------

    fun captureState(): FuguSessionState {
        val state = FuguSessionState()
        state.threadId = clientRef?.threadId ?: pendingThreadId
        state.title = title
        state.messages = messages.mapTo(mutableListOf()) { it.toPersisted() }
        return state
    }

    fun restoreState(state: FuguSessionState) {
        title = state.title
        pendingThreadId = state.threadId
        clientRef?.restoreThread(state.threadId)
        messages.clear()
        state.messages.forEach { messages.add(it.toRuntime()) }
        // A resumed thread already carries its injected context; only a tab that
        // never ran a turn should inject on its first send.
        agentContextSent = messages.isNotEmpty()
    }

    private fun refreshProjectFiles() {
        ApplicationManager.getApplication().invokeLater {
            VirtualFileManager.getInstance().asyncRefresh(null)
        }
    }

    private inline fun onEdt(crossinline body: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ body() }, project.disposed)
    }

    private inline fun notify(action: (Listener) -> Unit) {
        listeners.forEach(action)
    }

    override fun dispose() {
        clientRef?.stop()
        listeners.clear()
    }

    private companion object {
        /** CSI / SGR ANSI escape sequences (e.g. ESC[31m) to strip from log lines. */
        val ANSI_ESCAPE = Regex("\\[[0-9;?]*[ -/]*[@-~]")
    }
}
