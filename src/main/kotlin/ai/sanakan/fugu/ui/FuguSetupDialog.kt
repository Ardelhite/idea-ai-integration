package ai.sanakan.fugu.ui

import ai.sanakan.fugu.core.CodexConfig
import ai.sanakan.fugu.core.CodexInstaller
import ai.sanakan.fugu.core.SakanaApi
import ai.sanakan.fugu.settings.FuguSecrets
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * One-stop, terminal-free setup for Fugu. Covers everything that previously
 * required shell commands:
 *  - paste & verify the Sakana API key (stored in PasswordSafe),
 *  - install the Codex CLI (runs the installer with the key already in env),
 *  - write the `[model_providers.sakana]` block into `config.toml`.
 */
class FuguSetupDialog(private val project: Project) : DialogWrapper(project) {

    private val apiKeyField = JBPasswordField().apply { columns = 28 }
    private val verifyButton = JButton("Verify")
    private val keyStatus = statusLabel()

    private val codexStatus = statusLabel()
    private val installButton = JButton("Install Codex CLI")

    private val providerStatus = statusLabel()
    private val configButton = JButton("Write provider config")

    private val log = JBTextArea(6, 50).apply {
        isEditable = false
        lineWrap = true
    }

    init {
        title = "Set up Karato"
        setOKButtonText("Done")
        apiKeyField.text = FuguSecrets.getApiKey() ?: ""
        wire()
        init()
        refreshStatus()
    }

    override fun createCenterPanel(): JComponent {
        val keyRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(apiKeyField, BorderLayout.CENTER)
            add(verifyButton, BorderLayout.EAST)
        }
        val getKeyLink = HyperlinkLabel("Create an API key at console.sakana.ai").apply {
            addHyperlinkListener { BrowserUtil.browse(FuguSecrets.CONSOLE_KEYS_URL) }
        }
        val panel = FormBuilder.createFormBuilder()
            .addComponent(section("1. Sakana API key"))
            .addLabeledComponent("API key:", keyRow, true)
            .addComponentToRightColumn(getKeyLink)
            .addComponentToRightColumn(keyStatus)
            .addSeparator()
            .addComponent(section("2. Codex CLI"))
            .addLabeledComponent("Status:", codexStatus, true)
            .addComponentToRightColumn(installButton)
            .addSeparator()
            .addComponent(section("3. Sakana provider (config.toml)"))
            .addLabeledComponent("Status:", providerStatus, true)
            .addComponentToRightColumn(configButton)
            .addSeparator()
            .addComponent(JBLabel("Log:"))
            .addComponentFillVertically(JBScrollPane(log).apply { preferredSize = Dimension(JBUI.scale(560), JBUI.scale(120)) }, 0)
            .panel
        panel.preferredSize = Dimension(JBUI.scale(600), JBUI.scale(440))
        return panel
    }

    private fun wire() {
        verifyButton.addActionListener { verifyKey() }
        installButton.addActionListener { installCodex() }
        configButton.addActionListener { writeProviderConfig() }
    }

    // --- actions ---------------------------------------------------------------

    private fun persistKey(): String {
        val key = String(apiKeyField.password).trim()
        FuguSecrets.setApiKey(key.ifEmpty { null })
        return key
    }

    private fun verifyKey() {
        val key = persistKey()
        if (key.isEmpty()) {
            setStatus(keyStatus, "Enter a key first", ok = false)
            return
        }
        verifyButton.isEnabled = false
        setStatus(keyStatus, "Verifying…", ok = null)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = SakanaApi.verifyKey(key)
            onEdt {
                verifyButton.isEnabled = true
                result.fold(
                    onSuccess = { setStatus(keyStatus, "Key is valid ✓", ok = true) },
                    onFailure = { setStatus(keyStatus, "Verification failed: ${it.message}", ok = false) },
                )
            }
        }
    }

    private fun installCodex() {
        persistKey()
        installButton.isEnabled = false
        appendLog("$ ${CodexInstaller.INSTALL_COMMAND}")
        CodexInstaller.install(project, object : CodexInstaller.Output {
            override fun line(text: String) = onEdt { appendLog(text) }
            override fun done(exitCode: Int) = onEdt {
                appendLog(if (exitCode == 0) "✓ Installer finished." else "✗ Installer exited with code $exitCode.")
                installButton.isEnabled = true
                refreshStatus()
            }

            override fun failed(message: String) = onEdt {
                appendLog("✗ Failed to launch installer: $message")
                installButton.isEnabled = true
            }
        })
    }

    private fun writeProviderConfig() {
        val target = CodexConfig.configFile().path
        if (CodexConfig.hasSakanaProvider()) {
            setStatus(providerStatus, "Already configured", ok = true)
            return
        }
        val proceed = Messages.showOkCancelDialog(
            project,
            "Append the Sakana provider block to:\n\n    $target\n\n" +
                "This adds a [model_providers.sakana] section so Codex can reach Fugu. " +
                "Your existing config is preserved.",
            "Write provider config",
            "Write",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        if (proceed != Messages.OK) return
        runCatching { CodexConfig.ensureSakanaProvider() }.fold(
            onSuccess = { wrote ->
                appendLog(if (wrote) "✓ Wrote provider block to $target" else "Provider already present.")
                refreshStatus()
            },
            onFailure = { setStatus(providerStatus, "Write failed: ${it.message}", ok = false) },
        )
    }

    // --- status ----------------------------------------------------------------

    private fun refreshStatus() {
        val codex = CodexInstaller.resolve()
        setStatus(codexStatus, codex?.let { "Installed: ${it.path}" } ?: "Not found", ok = codex != null)

        val configured = CodexConfig.hasSakanaProvider()
        setStatus(
            providerStatus,
            if (configured) "Configured (${CodexConfig.configFile().path})" else "Not configured",
            ok = configured,
        )

        if (FuguSecrets.getApiKey() != null && keyStatus.text.isNullOrBlank()) {
            setStatus(keyStatus, "Key stored (click Verify to check)", ok = null)
        }
    }

    private fun statusLabel() = JBLabel().apply { foreground = JBColor.GRAY }

    private fun setStatus(label: JBLabel, text: String, ok: Boolean?) {
        label.text = text
        label.foreground = when (ok) {
            true -> JBColor(0x059669, 0x4ADE80)
            false -> JBColor.RED
            null -> JBColor.GRAY
        }
    }

    private fun section(title: String) = JBLabel("<html><b>$title</b></html>")

    private fun appendLog(line: String) {
        log.append(line + "\n")
        log.caretPosition = log.document.length
    }

    private fun onEdt(body: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) body() }, ModalityState.any())
    }

    override fun doOKAction() {
        persistKey()
        super.doOKAction()
    }
}
