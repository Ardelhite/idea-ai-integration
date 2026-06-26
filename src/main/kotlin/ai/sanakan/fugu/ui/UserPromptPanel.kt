package ai.sanakan.fugu.ui

import ai.sanakan.fugu.cli.PromptField
import ai.sanakan.fugu.cli.PromptFieldType
import ai.sanakan.fugu.cli.UserPrompt
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JPanel

/**
 * An inline form (not a popup) that renders a structured agent prompt
 * ([UserPrompt]) right above the composer. Radios for single-select (with an
 * optional free-text "Other"), checkboxes for multi-select, text/secret/boolean
 * otherwise. Calls [onSubmit] with field id → values, or [onCancel] on Skip.
 */
class UserPromptPanel(
    prompt: UserPrompt,
    private val onSubmit: (Map<String, List<String>>) -> Unit,
    private val onCancel: () -> Unit,
) : JPanel(BorderLayout()) {

    private val collectors = mutableListOf<Pair<String, () -> List<String>>>()

    init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0),
            JBUI.Borders.empty(8, 10),
        )

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        content.add(left(JBLabel("Fugu needs your input").apply {
            foreground = JBColor(0x2563EB, 0x6AA0FF)
            font = font.deriveFont(Font.BOLD, font.size - 1f)
        }))
        prompt.message?.takeIf { it.isNotBlank() }?.let {
            content.add(left(JBLabel("<html>${escape(it)}</html>").apply { border = JBUI.Borders.emptyTop(2) }))
        }
        for (field in prompt.fields) {
            content.add(left(fieldComponent(field)))
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        val skip = JButton("Skip").apply { addActionListener { onCancel() } }
        val submit = JButton("Submit").apply { addActionListener { onSubmit(answers()) } }
        buttons.add(skip)
        buttons.add(submit)

        add(content, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }

    private fun fieldComponent(field: PromptField): JPanel {
        val box = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
        }
        box.add(left(JBLabel(field.title).apply { font = font.deriveFont(Font.BOLD) }))
        field.description?.takeIf { it.isNotBlank() && it != field.title }?.let {
            box.add(left(JBLabel("<html>${escape(it)}</html>").apply { foreground = JBColor.GRAY }))
        }
        when (field.type) {
            PromptFieldType.SINGLE_SELECT -> addSingleSelect(box, field)
            PromptFieldType.MULTI_SELECT -> addMultiSelect(box, field)
            PromptFieldType.BOOLEAN -> {
                val cb = JBCheckBox(field.title)
                box.add(left(cb))
                collectors += field.id to { listOf(cb.isSelected.toString()) }
            }
            PromptFieldType.NUMBER, PromptFieldType.TEXT -> {
                val tf = if (field.secret) JBPasswordField() else JBTextField()
                (tf as? JBTextField)?.columns = 28
                box.add(left(tf))
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
            box.add(left(rb))
        }
        var otherField: JBTextField? = null
        var otherButton: JBRadioButton? = null
        if (field.allowOther) {
            val rb = JBRadioButton("Other:")
            val tf = JBTextField().apply { columns = 22 }
            group.add(rb)
            otherButton = rb
            otherField = tf
            box.add(left(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(rb)
                add(tf)
            }))
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
        val checks = field.options.map { opt -> JBCheckBox(opt.label).also { box.add(left(it)) } to opt.value }
        collectors += field.id to { checks.filter { it.first.isSelected }.map { it.second } }
    }

    private fun answers(): Map<String, List<String>> =
        collectors.associate { (id, get) -> id to get() }.filterValues { it.isNotEmpty() }

    private fun <T : Component> left(c: T): T = c.apply { (this as? javax.swing.JComponent)?.alignmentX = Component.LEFT_ALIGNMENT }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
