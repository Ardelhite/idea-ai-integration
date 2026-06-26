package ai.sanakan.fugu.core

import ai.sanakan.fugu.settings.FuguEnv
import ai.sanakan.fugu.settings.FuguSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
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
 * Fugu's documented installer wires the Codex CLI to the Sakana provider:
 *   curl -fsSL https://sakana.ai/fugu/install | bash
 * and requires `SAKANA_API_KEY` to be set in the environment.
 */
object CodexInstaller {

    const val INSTALL_COMMAND: String = "curl -fsSL https://sakana.ai/fugu/install | bash"
    const val CONSOLE_URL: String = "https://console.sakana.ai/get-started"

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
    fun install(project: Project, output: Output) {
        val cmd = GeneralCommandLine("/bin/bash", "-lc", INSTALL_COMMAND).apply {
            charset = StandardCharsets.UTF_8
            // Inject the stored key so the installer's verification step works without `export`.
            withEnvironment(FuguEnv.codexEnvironment())
            project.basePath?.let { setWorkDirectory(it) }
        }

        object : Task.Backgroundable(project, "Installing Codex (Fugu)", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val handler = OSProcessHandler(cmd)
                    handler.addProcessListener(object : ProcessAdapter() {
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            event.text.lineSequence().forEach { if (it.isNotBlank()) output.line(it.trimEnd()) }
                        }

                        override fun processTerminated(event: ProcessEvent) = output.done(event.exitCode)
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
