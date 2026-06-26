package ai.sanakan.fugu.ui

import ai.sanakan.fugu.cli.PromptField
import ai.sanakan.fugu.cli.PromptFieldType
import ai.sanakan.fugu.cli.UserPrompt
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Renders a structured agent prompt ([UserPrompt]) — MCP elicitation or a tool's
 * requestUserInput — as a form: radio buttons for single-select (with optional
 * free-text "Other"), checkboxes for multi-select, and text/secret/boolean/number
 * fields otherwise. [answers] collects field id → selected/entered values.
 */
class FuguUserInputDialog(project: Project, private val prompt: UserPrompt) : DialogWrapper(project) {

    private val collectors = mutableListOf<Pair<String, () -> List<String>>>()

    init {
        title = "Fugu needs your input"
        setOKButtonText("Submit")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
        }
        prompt.message?.takeIf { it.isNotBlank() }?.let {
            root.add(wrap(JBLabel("<html>${escape(it)}</html>").apply { border = JBUI.Borders.emptyBottom(8) }))
        }
        for (field in prompt.fields) {
            root.add(wrap(fieldComponent(field)))
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(root)
            preferredSize = Dimension(JBUI.scale(460), preferredSize.height.coerceIn(JBUI.scale(120), JBUI.scale(560)))
        }
    }

    private fun fieldComponent(field: PromptField): JComponent {
        val box = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(12)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        box.add(wrap(JBLabel(field.title).apply { font = font.deriveFont(Font.BOLD) }))
        field.description?.takeIf { it.isNotBlank() && it != field.title }?.let {
            box.add(wrap(JBLabel("<html>${escape(it)}</html>").apply { foreground = com.intellij.ui.JBColor.GRAY }))
        }

        when (field.type) {
            PromptFieldType.SINGLE_SELECT -> addSingleSelect(box, field)
            PromptFieldType.MULTI_SELECT -> addMultiSelect(box, field)
            PromptFieldType.BOOLEAN -> {
                val cb = JBCheckBox(field.title)
                box.add(wrap(cb))
                collectors += field.id to { listOf(cb.isSelected.toString()) }
            }
            PromptFieldType.NUMBER, PromptFieldType.TEXT -> {
                val tf = if (field.secret) JBPasswordField() else JBTextField()
                (tf as? JBTextField)?.columns = 30
                box.add(wrap(tf))
                collectors += field.id to {
                    val text = if (tf is JBPasswordField) String(tf.password) else (tf as JBTextField).text
                    if (text.isBlank()) emptyList() else listOf(text)
                }
            }
        }
        return box
    }

    private fun addSingleSelect(box: JPanel, field: PromptField) {
        val group = ButtonGroup()
        val buttons = mutableListOf<Pair<JBRadioButton, String>>()
        field.options.forEachIndexed { i, opt ->
            val rb = JBRadioButton(opt.label).apply { isSelected = i == 0 }
            group.add(rb)
            buttons += rb to opt.value
            box.add(wrap(rb))
        }
        var otherField: JBTextField? = null
        var otherButton: JBRadioButton? = null
        if (field.allowOther) {
            val rb = JBRadioButton("Other:")
            val tf = JBTextField().apply { columns = 24 }
            group.add(rb)
            otherButton = rb
            otherField = tf
            val row = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(rb)
                add(tf)
            }
            box.add(wrap(row))
            if (field.options.isEmpty()) rb.isSelected = true
        }
        collectors += field.id to {
            when {
                otherButton?.isSelected == true -> otherField?.text?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
                else -> buttons.firstOrNull { it.first.isSelected }?.let { listOf(it.second) } ?: emptyList()
            }
        }
    }

    private fun addMultiSelect(box: JPanel, field: PromptField) {
        val checks = field.options.map { opt ->
            JBCheckBox(opt.label).also { box.add(wrap(it)) } to opt.value
        }
        collectors += field.id to { checks.filter { it.first.isSelected }.map { it.second } }
    }

    /** Field id → selected/entered values, after the dialog is accepted. */
    fun answers(): Map<String, List<String>> =
        collectors.associate { (id, get) -> id to get() }.filterValues { it.isNotEmpty() }

    private fun wrap(c: JComponent): JComponent = c.apply { alignmentX = Component.LEFT_ALIGNMENT }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
