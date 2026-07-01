package ai.sanakan.fugu.core

import ai.sanakan.fugu.settings.FuguEnv
import ai.sanakan.fugu.settings.FuguSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Detects and installs the Codex CLI used to reach Fugu, the way Claude-Code GUIs
 * offer a one-click "install the agent CLI" affordance.
 *
 * Fugu's documented one-liner is `curl -fsSL https://sakana.ai/fugu/install | bash`,
 * but that bootstrap re-`exec`s the real installer with stdin redirected from
 * `/dev/tty`, which fails ("Device not configured") when launched from the IDE
 * with no controlling terminal. So we replicate the bootstrap directly — clone
 * `SakanaAI/fugu` and run `scripts/install.sh` — in fully non-interactive mode
 * (`--yes --force`), with `SAKANA_API_KEY` injected so no prompt is needed.
 */
object CodexInstaller {

    const val CONSOLE_URL: String = "https://console.sakana.ai/get-started"

    /** Shown to the user in the install log instead of the raw multi-line script. */
    const val INSTALL_SUMMARY: String =
        "git clone https://github.com/SakanaAI/fugu.git ~/.fugu && bash ~/.fugu/scripts/install.sh --yes --force"

    private val repoDir: String
        get() = System.getenv("FUGU_HOME")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/.fugu")

    /** The actual bash script run by [install] (bootstrap minus the /dev/tty redirect). */
    private fun installScript(): String {
        val dir = repoDir
        return buildString {
            append("set -e\n")
            append("export GIT_TERMINAL_PROMPT=0\n")
            append("if [ ! -d \"$dir/.git\" ]; then git clone --depth 1 https://github.com/SakanaAI/fugu.git \"$dir\"; fi\n")
            append("exec bash \"$dir/scripts/install.sh\" --yes --force\n")
        }
    }

    interface Output {
        fun line(text: String)
        fun done(exitCode: Int)
        fun failed(message: String)
    }

    /** Resolves the configured/known Codex executable, or null if none is on PATH. */
    fun resolve(): File? {
        val configured = FuguSettings.getInstance().cliPath.trim()
        if (configured.isNotEmpty()) {
            val f = File(configured)
            if (f.isAbsolute) {
                if (f.canExecute()) return f
            } else {
                PathEnvironmentVariableUtil.findInPath(configured)?.let { return it }
            }
        }
        return PathEnvironmentVariableUtil.findInPath("codex-fugu")
            ?: PathEnvironmentVariableUtil.findInPath("codex")
    }

    fun isInstalled(): Boolean = resolve() != null

    /** True once a `codex-fugu` launcher is present; lets callers prefer it. */
    fun hasFuguLauncher(): Boolean = PathEnvironmentVariableUtil.findInPath("codex-fugu") != null

    /** Runs the Fugu installer in the background, streaming output to [output]. */
    fun install(project: Project?, output: Output) {
        val cmd = GeneralCommandLine("/bin/bash", "-lc", installScript()).apply {
            charset = StandardCharsets.UTF_8
            // Inject the stored key (so the installer needs no prompt) and force
            // non-interactive mode, since there is no terminal to answer prompts.
            withEnvironment(FuguEnv.codexEnvironment())
            withEnvironment(mapOf("FUGU_ASSUME_YES" to "1", "FUGU_FORCE" to "1", "GIT_TERMINAL_PROMPT" to "0"))
            project?.basePath?.let { setWorkDirectory(it) }
        }

        object : Task.Backgroundable(project, "Installing Codex (Fugu)", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val handler = OSProcessHandler(cmd)
                    handler.addProcessListener(object : ProcessListener {
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            event.text.lineSequence().forEach { if (it.isNotBlank()) output.line(it.trimEnd()) }
                        }

                        override fun processTerminated(event: ProcessEvent) {
                            CodexVersion.invalidate() // a fresh install may change the version
                            output.done(event.exitCode)
                        }
                    })
                    handler.startNotify()
                    while (!handler.isProcessTerminated) {
                        if (indicator.isCanceled) {
                            handler.destroyProcess()
                            break
                        }
                        Thread.sleep(100)
                    }
                } catch (t: Throwable) {
                    output.failed(t.message ?: t.javaClass.simpleName)
                }
            }
        }.queue()
    }
}
