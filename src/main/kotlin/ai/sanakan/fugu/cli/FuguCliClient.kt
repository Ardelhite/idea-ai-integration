package ai.sanakan.fugu.cli

import ai.sanakan.fugu.settings.FuguEnv
import ai.sanakan.fugu.settings.FuguSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.intellij.util.io.BaseOutputReader
import com.intellij.util.text.nullize
import java.nio.charset.StandardCharsets

/**
 * Drives the Codex CLI (configured for the Sakana/Fugu provider).
 *
 * Unlike a persistent-stdin agent, `codex exec` is one-shot per turn: each user
 * message spawns a fresh process. Multi-turn continuity is achieved by capturing
 * the `thread_id` from the first turn's `thread.started` event and replaying it
 * with `codex exec resume <thread_id>` on subsequent turns.
 */
class FuguCliClient(
    private val workingDir: String,
    private val listener: FuguAgentListener,
) : FuguTransport {

    private val log = logger<FuguCliClient>()
    private var handler: OSProcessHandler? = null

    /** Carried across turns to resume the same Codex session; persisted by the session. */
    override var threadId: String? = null
        private set

    override val isRunning: Boolean
        get() = handler?.let { !it.isProcessTerminated } ?: false

    /** Restores a thread id loaded from persisted state so the next turn resumes it. */
    override fun restoreThread(id: String?) {
        threadId = id
    }

    /** Begins a new conversation; the next [send] starts a fresh thread. */
    override fun reset() {
        stop()
        threadId = null
    }

    /** Runs one turn. Spawns `codex exec [resume <id>] … <prompt>`. */
    override fun send(prompt: String, model: String) {
        if (isRunning) return
        val settings = FuguSettings.getInstance()
        val exe = settings.cliPath.nullize(nullizeSpaces = true) ?: "codex"

        val cmd = GeneralCommandLine(exe).apply {
            addParameter("exec")
            threadId?.let { addParameters("resume", it) }
            addParameter("--json")
            addParameters("-m", model)
            if (settings.sakanaProvider) addParameters("-c", "model_provider=sakana")
            val mode = settings.permissionModeEnum
            if (mode.bypass) {
                addParameter("--dangerously-bypass-approvals-and-sandbox")
            } else {
                addParameters("--sandbox", mode.sandbox)
                // The workspace-write sandbox blocks network by default; allow it so gh/curl/npm work.
                if (settings.allowNetwork && mode.sandbox == "workspace-write") {
                    addParameters("-c", "sandbox_workspace_write.network_access=true")
                }
                // exec is headless: `on-request` would be auto-declined, so clamp to never.
                addParameters("-a", if (mode.approval == "on-request") "never" else mode.approval)
            }
            addParameter("--skip-git-repo-check")
            settings.extraArgs.nullize(nullizeSpaces = true)?.let { extra ->
                addParameters(*extra.split(Regex("\\s+")).toTypedArray())
            }
            // Prompt is the trailing positional argument.
            addParameter(prompt)
            setWorkDirectory(workingDir)
            charset = StandardCharsets.UTF_8
            // Inject the API key (if stored) so Codex can reach the Sakana provider.
            withEnvironment(FuguEnv.codexEnvironment())
        }

        try {
            val osHandler = object : OSProcessHandler(cmd) {
                override fun readerOptions(): BaseOutputReader.Options = BaseOutputReader.Options.forMostlySilentProcess()
            }
            osHandler.addProcessListener(StdoutPump())
            osHandler.startNotify()
            handler = osHandler
            log.info("codex exec started: ${cmd.commandLineString}")
        } catch (t: Throwable) {
            log.warn("Failed to start Codex CLI", t)
            listener.onStartFailed(
                "Could not start the Codex CLI ('$exe'). Install it via the Fugu one-line installer " +
                    "and check the path in Settings → Tools → Karato.\n${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    override fun stop() {
        handler?.destroyProcess()
        handler = null
    }

    private inner class StdoutPump : ProcessAdapter() {
        private val buffer = StringBuilder()

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            when (outputType) {
                ProcessOutputType.STDOUT -> pumpStdout(event.text)
                ProcessOutputType.STDERR -> event.text.lineSequence().forEach {
                    if (it.isNotEmpty()) listener.onStderr(it)
                }
            }
        }

        private fun pumpStdout(text: String) {
            buffer.append(text)
            var newline = buffer.indexOf("\n")
            while (newline >= 0) {
                emit(buffer.substring(0, newline))
                buffer.delete(0, newline + 1)
                newline = buffer.indexOf("\n")
            }
        }

        private fun emit(line: String) {
            if (line.isBlank()) return
            val parsed = StreamJsonParser.parseLine(line)
            // Capture the session id so the next turn can resume it.
            if (parsed is FuguEvent.Init) parsed.sessionId?.let { threadId = it }
            listener.onEvent(parsed)
        }

        override fun processTerminated(event: ProcessEvent) {
            if (buffer.isNotBlank()) emit(buffer.toString())
            buffer.setLength(0)
            handler = null
            listener.onProcessTerminated(event.exitCode)
        }
    }
}
