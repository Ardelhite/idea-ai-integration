package ai.sanakan.fugu.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * How much autonomy the agent is granted. Maps onto Codex's `--sandbox` and
 * `--ask-for-approval` flags. Approval is forced to "never" because `codex exec`
 * runs non-interactively — there is no channel to answer a prompt mid-turn.
 */
enum class FuguPermissionMode(
    /** Short, user-facing mode name shown in the chat dropdown. */
    val modeLabel: String,
    val display: String,
    val sandbox: String,
    val approval: String,
    val bypass: Boolean,
) {
    ASK("Default", "Ask before each edit or command", "workspace-write", "on-request", false),
    ACCEPT_EDITS("Auto", "Auto-edit the workspace, no prompts", "workspace-write", "never", false),
    FULL_ACCESS("Agent", "Full access — no sandbox, no prompts", "danger-full-access", "never", true),
    READ_ONLY("Plan", "Read-only — inspect/plan, no edits", "read-only", "never", false);

    companion object {
        fun fromName(value: String?): FuguPermissionMode =
            entries.firstOrNull { it.name == value } ?: ASK
    }
}

/** Which transport drives the agent. */
enum class FuguTransportKind(val display: String) {
    APP_SERVER("codex app-server (interactive approvals)"),
    EXEC("codex exec (headless, no approvals)");

    companion object {
        fun fromName(value: String?): FuguTransportKind =
            entries.firstOrNull { it.name == value } ?: APP_SERVER
    }
}

/**
 * Which MCP servers (mirrored from Claude Code's config) Codex should launch.
 *  - OFF      none
 *  - PROJECT  this project's servers (`.mcp.json` + `~/.claude.json` project-local)
 *  - ALL      the above plus user-global servers (`~/.claude.json` top-level)
 */
enum class McpMode(val display: String) {
    OFF("MCP: Off"),
    PROJECT("MCP: Project"),
    ALL("MCP: All");

    companion object {
        fun fromName(value: String?): McpMode =
            entries.firstOrNull { it.name == value } ?: ALL
    }
}

/** Which keystroke submits the message. */
enum class SendShortcut(val display: String) {
    ENTER("Enter to send (⇧⏎ for newline)"),
    MODIFIER_ENTER("⌘/Alt + Enter to send (⏎ for newline)");

    companion object {
        fun fromName(value: String?): SendShortcut =
            entries.firstOrNull { it.name == value } ?: ENTER
    }
}

/**
 * Application-wide, persisted configuration for the Fugu integration.
 *
 * Fugu has no standalone binary: it is reached through the Codex CLI with the
 * Sakana provider (`-c model_provider=sakana`) or via the `codex-fugu` launcher.
 */
@State(
    name = "ai.sanakan.fugu.FuguSettings",
    storages = [Storage("sanakan-fugu.xml")],
)
class FuguSettings : PersistentStateComponent<FuguSettings> {

    /** Path to (or name of) the Codex executable: `codex` or `codex-fugu`. */
    var cliPath: String = "codex"

    /** Model alias passed via `-m`. "fugu" or "fugu-ultra". */
    var model: String = "fugu"

    /** Which transport drives the agent, stored by enum name. */
    var transport: String = FuguTransportKind.APP_SERVER.name

    /** Permission mode (sandbox + approval), stored by enum name. */
    var permissionMode: String = FuguPermissionMode.ASK.name

    /** Add `-c model_provider=sakana`. Leave on for plain `codex`; off for `codex-fugu`. */
    var sakanaProvider: Boolean = true

    /** Extra args appended verbatim to every `codex exec` invocation. */
    var extraArgs: String = ""

    /** Inject Claude/Codex project files (CLAUDE.md, .claude/, memory) at thread start. */
    var loadAgentContext: Boolean = true

    /**
     * Allow outbound network in the `workspace-write` sandbox (so `gh`, `curl`, `npm`,
     * etc. work). Codex blocks network in that sandbox by default; when on, the launch
     * adds `-c sandbox_workspace_write.network_access=true`.
     */
    var allowNetwork: Boolean = true

    /** Which Claude-mirrored MCP servers Codex should launch, stored by enum name. */
    var mcpMode: String = McpMode.ALL.name

    val mcpModeEnum: McpMode
        get() = McpMode.fromName(mcpMode)

    /** Which keystroke submits the message, stored by enum name. */
    var sendShortcut: String = SendShortcut.ENTER.name

    val sendShortcutEnum: SendShortcut
        get() = SendShortcut.fromName(sendShortcut)

    override fun getState(): FuguSettings = this

    override fun loadState(state: FuguSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    val permissionModeEnum: FuguPermissionMode
        get() = FuguPermissionMode.fromName(permissionMode)

    val transportKind: FuguTransportKind
        get() = FuguTransportKind.fromName(transport)

    companion object {
        @JvmStatic
        fun getInstance(): FuguSettings =
            ApplicationManager.getApplication().getService(FuguSettings::class.java)

        /** Known model aliases shown in the UI dropdown. */
        val KNOWN_MODELS = listOf("fugu", "fugu-ultra")

        private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<Runnable>()

        /** Subscribe to settings-applied notifications (e.g. to refresh open UI). */
        fun addChangeListener(listener: Runnable) { changeListeners.add(listener) }

        fun removeChangeListener(listener: Runnable) { changeListeners.remove(listener) }

        /** Invoked by the settings page after `apply()` so open panels can react. */
        fun fireChanged() { changeListeners.forEach { it.run() } }
    }
}
