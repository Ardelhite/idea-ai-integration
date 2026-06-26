package ai.sanakan.fugu.ui

import ai.sanakan.fugu.core.ChatMessage
import ai.sanakan.fugu.core.ChatRole
import ai.sanakan.fugu.core.ToolCall
import com.intellij.ide.BrowserUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkEvent

/**
 * Renders a single [ChatMessage] as a bordered bubble with a role header, a
 * Markdown-rendered body, and a card per tool call.
 *
 * Height tracks content: [getMaximumSize] returns the live preferred height so a
 * vertical [BoxLayout] never clips a growing (streaming) message or its tool
 * cards. The body is an [JEditorPane] that re-wraps to its allotted width.
 */
class MessageComponent(
    private val message: ChatMessage,
    private val onToolClicked: (ToolCall) -> Unit = {},
) : JPanel(BorderLayout()) {

    private val body = object : JEditorPane() {
        // Compute the wrapped height for whatever width the layout gives us.
        override fun getPreferredSize(): Dimension {
            if (width > 0) setSize(width, Int.MAX_VALUE)
            return super.getPreferredSize()
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        override fun getMinimumSize(): Dimension = Dimension(0, 0)
    }.apply {
        editorKit = UIUtil.getHTMLEditorKit()
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        alignmentX = Component.LEFT_ALIGNMENT
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) e.url?.let { BrowserUtil.browse(it) }
        }
    }

    private val toolsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
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

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private fun header(): Component =
        JBLabel(roleLabel(message.role)).apply {
            horizontalAlignment = SwingConstants.LEFT
            foreground = headerColor(message.role)
            font = font.deriveFont(font.style or Font.BOLD, font.size - 1f)
            border = JBUI.Borders.emptyBottom(4)
        }

    /** Re-syncs the Swing widgets with the (possibly mutated) message. */
    fun refresh() {
        val txt = message.text.toString()
        val markdown = if (message.streaming && txt.isEmpty()) "…" else txt
        body.text = htmlDocument(markdown)
        body.isVisible = txt.isNotEmpty() || message.streaming

        toolsPanel.removeAll()
        for (call in message.toolCalls) {
            toolsPanel.add(toolCard(call))
        }
        toolsPanel.isVisible = message.toolCalls.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun htmlDocument(markdown: String): String {
        val font = UIUtil.getLabelFont()
        val fg = ColorUtil.toHtmlColor(UIUtil.getLabelForeground())
        val codeBg = ColorUtil.toHtmlColor(ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.12))
        val link = ColorUtil.toHtmlColor(JBColor(0x2563EB, 0x589DF6))
        return """
            <html><head><style>
            body { color:$fg; font-family:'${font.family}'; font-size:${font.size}pt; margin:0; padding:0; }
            p { margin:3px 0; }
            pre { background:$codeBg; padding:5px; margin:4px 0; font-family:monospace; }
            code { background:$codeBg; font-family:monospace; }
            h1,h2,h3,h4 { margin:6px 0 3px 0; }
            ul,ol { margin:2px 0 2px 16px; }
            a { color:$link; }
            </style></head><body>${MarkdownRenderer.toHtmlBody(markdown)}</body></html>
        """.trimIndent()
    }

    private fun toolCard(call: ToolCall): Component {
        val card = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(4, 8),
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        val statusIcon = when {
            call.isError -> "✗"
            call.result != null -> "✓"
            else -> "▸"
        }
        titleRow.add(JBLabel("$statusIcon  ${call.name}").apply {
            font = font.deriveFont(Font.BOLD)
            foreground = if (call.isError) JBColor.RED else UIUtil.getLabelForeground()
        })
        call.target?.let { titleRow.add(JBLabel(it).apply { foreground = JBColor.GRAY }) }
        card.add(titleRow, BorderLayout.NORTH)

        if (call.name == "Edit" && call.target != null) {
            card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
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
