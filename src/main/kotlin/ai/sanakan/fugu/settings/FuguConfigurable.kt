package ai.sanakan.fugu.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings UI under Preferences → Tools → Karato.
 */
class FuguConfigurable : Configurable {

    private val transportCombo = ComboBox(DefaultComboBoxModel(FuguTransportKind.entries.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { it.display }
    }
    private val cliPathField = TextFieldWithBrowseButton().apply {
        textField.columns = 30
        addBrowseFolderListener(
            "Select the Codex CLI",
            "Path to the codex / codex-fugu executable (or a mock under tools/)",
            null,
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(),
        )
    }
    private val modelCombo = ComboBox(DefaultComboBoxModel(FuguSettings.KNOWN_MODELS.toTypedArray())).apply {
        isEditable = true
    }
    private val permissionCombo = ComboBox(DefaultComboBoxModel(FuguPermissionMode.entries.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { it.display }
    }
    private val sendShortcutCombo = ComboBox(DefaultComboBoxModel(SendShortcut.entries.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { it.display }
    }
    private val sakanaProviderCheck =
        JBCheckBox("Add Sakana provider override (-c model_provider=sakana)")
    private val apiKeyField = JBPasswordField().apply { columns = 30 }
    private val apiKeyLink = HyperlinkLabel("Create an API key at console.sakana.ai").apply {
        addHyperlinkListener { BrowserUtil.browse(FuguSecrets.CONSOLE_KEYS_URL) }
    }
    private val extraArgsField = JBTextField(30)

    /** Baseline used to detect key edits without re-reading PasswordSafe on every keystroke. */
    private var loadedKey: String = ""

    override fun getDisplayName(): String = "Karato"

    override fun createComponent(): JComponent {
        val built = FormBuilder.createFormBuilder()
            .addLabeledComponent("Transport:", transportCombo, true)
            .addLabeledComponent("Codex CLI path:", cliPathField, true)
            .addLabeledComponent("Model:", modelCombo, true)
            .addLabeledComponent("Permission mode:", permissionCombo, true)
            .addLabeledComponent("Send shortcut:", sendShortcutCombo, true)
            .addComponent(sakanaProviderCheck)
            .addLabeledComponent("Sakana API key:", apiKeyField, true)
            .addComponentToRightColumn(apiKeyLink)
            .addLabeledComponent("Extra CLI args:", extraArgsField, true)
            .addTooltip("The key is stored securely (PasswordSafe) and passed to Codex as SAKANA_API_KEY.")
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return built
    }

    override fun isModified(): Boolean {
        val s = FuguSettings.getInstance()
        return (transportCombo.selectedItem as FuguTransportKind).name != s.transport ||
            cliPathField.text != s.cliPath ||
            (modelCombo.editor.item as? String ?: "") != s.model ||
            (permissionCombo.selectedItem as FuguPermissionMode).name != s.permissionMode ||
            (sendShortcutCombo.selectedItem as SendShortcut).name != s.sendShortcut ||
            sakanaProviderCheck.isSelected != s.sakanaProvider ||
            String(apiKeyField.password) != loadedKey ||
            extraArgsField.text != s.extraArgs
    }

    override fun apply() {
        val s = FuguSettings.getInstance()
        s.transport = (transportCombo.selectedItem as FuguTransportKind).name
        s.cliPath = cliPathField.text.trim().ifEmpty { "codex" }
        s.model = (modelCombo.editor.item as? String ?: "fugu").trim().ifEmpty { "fugu" }
        s.permissionMode = (permissionCombo.selectedItem as FuguPermissionMode).name
        s.sendShortcut = (sendShortcutCombo.selectedItem as SendShortcut).name
        s.sakanaProvider = sakanaProviderCheck.isSelected
        s.extraArgs = extraArgsField.text.trim()

        val key = String(apiKeyField.password)
        if (key != loadedKey) {
            FuguSecrets.setApiKey(key)
            loadedKey = key
        }
        FuguSettings.fireChanged()
    }

    override fun reset() {
        val s = FuguSettings.getInstance()
        transportCombo.selectedItem = s.transportKind
        cliPathField.text = s.cliPath
        modelCombo.editor.item = s.model
        permissionCombo.selectedItem = s.permissionModeEnum
        sendShortcutCombo.selectedItem = s.sendShortcutEnum
        sakanaProviderCheck.isSelected = s.sakanaProvider
        extraArgsField.text = s.extraArgs

        loadedKey = FuguSecrets.getApiKey() ?: ""
        apiKeyField.text = loadedKey
    }
}
