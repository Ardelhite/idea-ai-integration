package ai.sanakan.fugu.ui

import ai.sanakan.fugu.core.ChatMessage
import ai.sanakan.fugu.core.ChatRole
import ai.sanakan.fugu.core.ToolCall
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.event.HyperlinkEvent

/**
 * Renders a single [ChatMessage] as a bordered bubble with a role header, a
 * Markdown-rendered body, and a rounded card per tool call. Each tool card shows
 * the action, its target (file paths are links that open in the editor), and a
 * status indicator: a spinner while running, a green dot on success, red on error.
 *
 * Height tracks content via [getMaximumSize] so a vertical BoxLayout never clips
 * a growing (streaming) message or its tool cards.
 */
class MessageComponent(
    private val message: ChatMessage,
    private val projectBasePath: String? = null,
    private val onToolClicked: (ToolCall) -> Unit = {},
) : JPanel(BorderLayout()) {

    private val expandedTools = HashSet<String>()
    private val addColor = JBColor(0x1A9E5E, 0x4ADE80)
    private val removeColor = JBColor(0xD64545, 0xF87171)

    private val body = object : JEditorPane() {
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
        border = JBUI.Borders.emptyTop(4)
    }

    /** So tool cards (and their spinners) only rebuild when a tool's state changes. */
    private var lastToolSignature = ""

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

        val signature = message.toolCalls.joinToString("|") { "${it.id}:${it.result != null}:${it.isError}" }
        if (signature != lastToolSignature) {
            lastToolSignature = signature
            toolsPanel.removeAll()
            message.toolCalls.forEachIndexed { i, call ->
                if (i > 0) toolsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
                toolsPanel.add(toolCard(call))
            }
            toolsPanel.isVisible = message.toolCalls.isNotEmpty()
        }
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

    // --- tool cards ------------------------------------------------------------

    private fun toolCard(call: ToolCall): Component {
        val card = RoundedPanel().apply {
            layout = BorderLayout(0, JBUI.scale(5))
            border = JBUI.Borders.empty(5, 9)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply { isOpaque = false }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply { isOpaque = false }
        left.add(JBLabel(actionIcon(call.name)))
        left.add(JBLabel(actionLabel(call.name)).apply { font = font.deriveFont(Font.BOLD) })
        header.add(left, BorderLayout.WEST)
        header.add(statusComponent(call), BorderLayout.EAST)

        when {
            call.name == "Edit" && call.target != null -> {
                val center = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
                center.add(HyperlinkLabel(relativeTarget(call.target!!)).apply {
                    toolTipText = "Open ${call.target}"
                    addHyperlinkListener { onToolClicked(call) }
                })
                if (call.added > 0) center.add(JBLabel("+${call.added}").apply { foreground = addColor })
                if (call.removed > 0) center.add(JBLabel("−${call.removed}").apply { foreground = removeColor })
                header.add(center, BorderLayout.CENTER)
                card.add(header, BorderLayout.NORTH)
            }

            call.name == "Shell" && call.command != null -> {
                val expanded = expandedTools.contains(call.id)
                val chevron = JBLabel(if (expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight)
                val summary = JBLabel(shorten(call.command!!)).apply { foreground = JBColor.GRAY }
                val toggle = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    add(chevron)
                    add(summary)
                }
                header.add(toggle, BorderLayout.CENTER)
                card.add(header, BorderLayout.NORTH)

                val detail = JTextArea(call.command).apply {
                    isEditable = false
                    isOpaque = false
                    lineWrap = true
                    wrapStyleWord = false
                    font = UIUtil.getFont(UIUtil.FontSize.SMALL, Font(Font.MONOSPACED, Font.PLAIN, 12))
                    border = JBUI.Borders.empty(0, 18, 0, 0)
                    isVisible = expanded
                }
                card.add(detail, BorderLayout.CENTER)

                toggle.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        val now = !detail.isVisible
                        detail.isVisible = now
                        chevron.icon = if (now) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
                        if (now) expandedTools.add(call.id) else expandedTools.remove(call.id)
                        revalidate()
                        repaint()
                    }
                })
            }

            call.target != null -> {
                header.add(JBLabel(shorten(call.target!!)).apply {
                    foreground = JBColor.GRAY
                    toolTipText = call.target
                }, BorderLayout.CENTER)
                card.add(header, BorderLayout.NORTH)
            }

            else -> card.add(header, BorderLayout.NORTH)
        }

        call.result?.takeIf { it.isNotBlank() }?.let {
            header.toolTipText = it.lineSequence().take(12).joinToString("\n")
        }
        return card
    }

    private fun relativeTarget(target: String): String {
        val base = projectBasePath?.trimEnd('/')
        return when {
            base != null && target.startsWith("$base/") -> target.removePrefix("$base/")
            else -> target.substringAfterLast('/')
        }
    }

    private fun statusComponent(call: ToolCall): JComponent = when {
        call.isError -> JBLabel(DotIcon(JBColor(0xD64545, 0xF87171)))
        call.result != null -> JBLabel(DotIcon(JBColor(0x1A9E5E, 0x4ADE80)))
        else -> Spinner(JBColor(0xD9A40E, 0xF2C94C))
    }

    private fun actionLabel(name: String) = when (name) {
        "Edit" -> "Edit file"
        "Shell" -> "Run command"
        "WebSearch" -> "Web search"
        "Plan" -> "Plan"
        else -> name
    }

    private fun actionIcon(name: String): Icon = when (name) {
        "Edit" -> AllIcons.Actions.Edit
        "Shell" -> AllIcons.Debugger.Console
        "WebSearch" -> AllIcons.Actions.Search
        else -> AllIcons.Nodes.Plugin
    }

    private fun shorten(s: String, max: Int = 80): String =
        s.replace("\n", " ").let { if (it.length > max) it.take(max - 1) + "…" else it }

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

    // --- visual helpers --------------------------------------------------------

    /** A panel painted as a rounded rectangle with a subtle fill and border. */
    private class RoundedPanel : JPanel() {
        private val arc = JBUI.scale(12)

        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ColorUtil.mix(UIUtil.getListBackground(), UIUtil.getLabelForeground(), 0.07)
                g2.fillRoundRect(0, 0, width, height, arc, arc)
                g2.color = JBColor.border()
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /** A small filled circle status dot. */
    private class DotIcon(private val color: Color, private val d: Int = JBUI.scale(9)) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillOval(x, y, d, d)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth() = d
        override fun getIconHeight() = d
    }

    /** A small rotating arc spinner that animates only while showing. */
    private class Spinner(private val color: Color) : JComponent() {
        private val sizePx = JBUI.scale(14)
        private var angle = 0
        private val timer = Timer(80) { angle = (angle + 30) % 360; repaint() }

        init {
            preferredSize = Dimension(sizePx, sizePx)
            isOpaque = false
        }

        override fun addNotify() {
            super.addNotify()
            timer.start()
        }

        override fun removeNotify() {
            timer.stop()
            super.removeNotify()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val pad = JBUI.scale(2)
                g2.drawArc(pad, pad, width - 2 * pad, height - 2 * pad, angle, 280)
            } finally {
                g2.dispose()
            }
        }

        override fun getMaximumSize(): Dimension = preferredSize
    }
}
