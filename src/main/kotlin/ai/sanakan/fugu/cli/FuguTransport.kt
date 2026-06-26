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
}
