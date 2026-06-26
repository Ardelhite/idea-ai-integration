package ai.sanakan.fugu.cli

/**
 * A pluggable way to drive the agent. Two implementations exist:
 *  - [FuguCliClient]        — `codex exec --json` (one process per turn, no approvals)
 *  - [FuguAppServerClient]  — `codex app-server` (long-lived JSON-RPC, interactive approvals)
 *
 * The session selects one based on settings; both normalize their wire formats
 * into the shared [FuguEvent] model and report through [FuguAgentListener].
 */
interface FuguTransport {
    val isRunning: Boolean

    /** Codex thread/session id carried across turns and persisted for resume. */
    val threadId: String?

    fun restoreThread(id: String?)

    /** Clears state and begins a fresh thread on the next [send]. */
    fun reset()

    /** Runs one user turn. */
    fun send(prompt: String, model: String)

    fun stop()
}

/** What kind of action an approval request is gating. */
enum class ApprovalKind { COMMAND, FILE_CHANGE }

/** A decision the user can return for an approval request (app-server values). */
enum class ApprovalDecision(val wire: String) {
    ACCEPT("accept"),
    ACCEPT_FOR_SESSION("acceptForSession"),
    DECLINE("decline"),
    CANCEL("cancel"),
}

/** A pending approval surfaced to the UI. */
data class ApprovalRequest(
    val kind: ApprovalKind,
    val summary: String,
    val detail: String?,
)

// --- structured user-input prompts (MCP elicitation / tool requestUserInput) ---

enum class UserPromptKind { ELICITATION, TOOL_INPUT }

enum class PromptFieldType { SINGLE_SELECT, MULTI_SELECT, TEXT, BOOLEAN, NUMBER }

enum class PromptAction { ACCEPT, DECLINE, CANCEL }

data class PromptOption(val value: String, val label: String)

/** One question/field the agent wants the user to answer. */
data class PromptField(
    val id: String,
    val title: String,
    val description: String?,
    val type: PromptFieldType,
    val options: List<PromptOption> = emptyList(),
    val allowOther: Boolean = false,
    val secret: Boolean = false,
    val required: Boolean = true,
)

/** A normalized structured prompt asking the user to choose / fill in values. */
data class UserPrompt(
    val kind: UserPromptKind,
    val message: String?,
    val fields: List<PromptField>,
)

/**
 * Callbacks from a transport. All are invoked off the EDT (process reader
 * threads); implementers must marshal to the EDT before touching UI.
 */
interface FuguAgentListener {
    fun onEvent(event: FuguEvent)
    fun onStderr(line: String)
    fun onProcessTerminated(exitCode: Int)
    fun onStartFailed(message: String)

    /**
     * The agent is asking permission before acting. The implementer eventually
     * calls [respond] exactly once with a decision; the transport relays it back
     * over the wire. Default: auto-decline (transports without approvals never call this).
     */
    fun onApproval(request: ApprovalRequest, respond: (ApprovalDecision) -> Unit) {
        respond(ApprovalDecision.DECLINE)
    }

    /**
     * The agent wants the user to answer a structured prompt (choices / fields).
     * The implementer calls [respond] once with the action and, on accept, a map
     * of field id → selected/entered values. Default: decline.
     */
    fun onUserInput(prompt: UserPrompt, respond: (PromptAction, Map<String, List<String>>) -> Unit) {
        respond(PromptAction.DECLINE, emptyMap())
    }
}
