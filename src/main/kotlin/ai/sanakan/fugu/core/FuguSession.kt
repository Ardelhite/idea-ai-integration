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
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-project session: bridges the [FuguCliClient] subprocess and the UI, and
 * persists the transcript + Codex thread id into the project's workspace file so
 * the conversation survives tool-window reopens and IDE restarts.
 *
 * One assistant bubble is built per turn; agent messages append text and tool
 * items appear as cards on that bubble. All listener callbacks are dispatched on
 * the EDT so the chat panel can mutate Swing state directly.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ai.sanakan.fugu.FuguSession",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class FuguSession(private val project: Project) : Disposable, FuguAgentListener, PersistentStateComponent<FuguSessionState> {

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

    /** Selected model for this session; defaults from settings, changeable in UI. */
    var model: String = FuguSettings.getInstance().model

    private var currentAssistant: ChatMessage? = null
    private var turnActive = false

    /** Item id of the message currently being streamed, to detect message boundaries. */
    private var streamingItemId: String? = null

    /** Cumulative output tokens this session (Sakana exposes no account-level usage API). */
    private var sessionOutputTokens = 0L

    private val client: FuguTransport by lazy {
        val dir = project.basePath ?: System.getProperty("user.dir")
        when (FuguSettings.getInstance().transportKind) {
            FuguTransportKind.EXEC -> FuguCliClient(dir, this)
            FuguTransportKind.APP_SERVER -> FuguAppServerClient(dir, this)
        }
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

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

        // Process launch can block briefly; keep it off the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            client.send(trimmed, model)
        }
    }

    fun stop() {
        client.stop()
        finishTurn()
    }

    /** Clears the transcript and starts a fresh Codex thread. */
    fun newConversation() {
        client.reset()
        messages.clear()
        currentAssistant = null
        turnActive = false
        streamingItemId = null
        sessionOutputTokens = 0L
        notify { it.onStatus("New conversation") }
    }

    // --- FuguCliClient.Listener (called from process reader threads) -----------

    override fun onEvent(event: FuguEvent) = onEdt {
        when (event) {
            is FuguEvent.Init ->
                notify { it.onStatus("Session ready (${event.model ?: model})") }

            is FuguEvent.AgentMessage -> {
                val msg = ensureAssistant()
                if (msg.text.isNotEmpty()) msg.appendText("\n\n")
                msg.appendText(event.text)
                notify { it.onMessageUpdated(msg) }
            }

            is FuguEvent.AgentMessageDelta -> {
                val msg = ensureAssistant()
                // A new message id within the turn → separate it from prior content.
                if (streamingItemId != event.itemId) {
                    if (msg.text.isNotEmpty()) msg.appendText("\n\n")
                    streamingItemId = event.itemId
                }
                msg.appendText(event.text)
                notify { it.onMessageUpdated(msg) }
            }

            is FuguEvent.ToolStarted -> {
                val msg = ensureAssistant()
                if (msg.toolCalls.none { it.id == event.id }) {
                    msg.toolCalls.add(ToolCall(event.id, event.name, event.input))
                    notify { it.onMessageUpdated(msg) }
                }
            }

            is FuguEvent.ToolCompleted -> {
                val msg = ensureAssistant()
                val existing = msg.toolCalls.firstOrNull { it.id == event.id }
                if (existing != null) {
                    existing.result = event.result
                    existing.isError = event.isError
                } else {
                    msg.toolCalls.add(ToolCall(event.id, event.name, event.input, event.result, event.isError))
                }
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
        notify { it.onStatus(line) }
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
        val msg = ChatMessage(ChatRole.ASSISTANT).apply { streaming = true }
        currentAssistant = msg
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
        if (msg.text.isNotEmpty()) msg.appendText("\n")
        msg.appendText(line)
        notify { it.onMessageUpdated(msg) }
    }

    // --- PersistentStateComponent ----------------------------------------------

    override fun getState(): FuguSessionState {
        val state = FuguSessionState()
        state.threadId = client.threadId
        state.messages = messages.mapTo(mutableListOf()) { it.toPersisted() }
        return state
    }

    override fun loadState(state: FuguSessionState) {
        messages.clear()
        state.messages.forEach { messages.add(it.toRuntime()) }
        client.restoreThread(state.threadId)
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
        client.stop()
        listeners.clear()
    }
}
