package ai.sanakan.fugu.ui

import ai.sanakan.fugu.core.ChatMessage
import ai.sanakan.fugu.core.ChatRole
import ai.sanakan.fugu.core.ToolCall
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants

/**
 * Renders a single [ChatMessage] as a bordered bubble with a role header,
 * body text, and a card per tool call. File-change cards are clickable and
 * invoke [onToolClicked] so the panel can open the affected file.
 */
class MessageComponent(
    private val message: ChatMessage,
    private val onToolClicked: (ToolCall) -> Unit = {},
) : JPanel(BorderLayout()) {

    private val body = JTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        font = UIUtil.getLabelFont()
    }
    private val toolsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        isOpaque = true
        background = backgroundFor(message.role)
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(8, 10),
        )
        alignmentX = Component.LEFT_ALIGNMENT

        add(header(), BorderLayout.NORTH)
        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        center.add(body)
        center.add(toolsPanel)
        add(center, BorderLayout.CENTER)
        refresh()
    }

    private fun header(): Component {
        val label = JBLabel(roleLabel(message.role)).apply {
            horizontalAlignment = SwingConstants.LEFT
            foreground = headerColor(message.role)
            font = font.deriveFont(font.style or java.awt.Font.BOLD, font.size - 1f)
            border = JBUI.Borders.emptyBottom(4)
        }
        return label
    }

    /** Re-syncs the Swing widgets with the (possibly mutated) message. */
    fun refresh() {
        val txt = message.text.toString()
        body.text = if (message.streaming && txt.isEmpty()) "…" else txt
        body.isVisible = txt.isNotEmpty() || message.streaming

        toolsPanel.removeAll()
        for (call in message.toolCalls) {
            toolsPanel.add(toolCard(call))
        }
        toolsPanel.isVisible = message.toolCalls.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun toolCard(call: ToolCall): Component {
        val card = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(4, 8),
            )
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        val statusIcon = when {
            call.isError -> "✗"
            call.result != null -> "✓"
            else -> "▸"
        }
        titleRow.add(JBLabel("$statusIcon  ${call.name}").apply {
            font = font.deriveFont(java.awt.Font.BOLD)
            foreground = if (call.isError) JBColor.RED else UIUtil.getLabelForeground()
        })
        call.target?.let { titleRow.add(JBLabel(it).apply { foreground = JBColor.GRAY }) }
        card.add(titleRow, BorderLayout.NORTH)

        // File-change cards open the affected file on click.
        if (call.name == "Edit" && call.target != null) {
            card.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            card.toolTipText = "Open ${call.target}"
            card.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = onToolClicked(call)
            })
        }

        call.result?.takeIf { it.isNotBlank() }?.let { result ->
            val preview = result.lineSequence().take(8).joinToString("\n")
            card.add(JTextArea(preview).apply {
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                font = UIUtil.getFont(UIUtil.FontSize.SMALL, null)
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyTop(2)
            }, BorderLayout.CENTER)
        }
        return card
    }

    private fun roleLabel(role: ChatRole) = when (role) {
        ChatRole.USER -> "You"
        ChatRole.ASSISTANT -> "Fugu"
        ChatRole.SYSTEM -> "System"
        ChatRole.ERROR -> "Error"
    }

    private fun headerColor(role: ChatRole) = when (role) {
        ChatRole.USER -> JBColor(0x2563EB, 0x6AA0FF)
        ChatRole.ASSISTANT -> JBColor(0x059669, 0x4ADE80)
        ChatRole.SYSTEM -> JBColor.GRAY
        ChatRole.ERROR -> JBColor.RED
    }

    private fun backgroundFor(role: ChatRole) = when (role) {
        ChatRole.USER -> UIUtil.getPanelBackground()
        ChatRole.ERROR -> JBColor(0xFDECEC, 0x4A2C2C)
        else -> UIUtil.getListBackground()
    }
}
