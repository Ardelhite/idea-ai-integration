package ai.sanakan.fugu.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * A custom-painted action button so its colors are honored across L&Fs: green
 * "Send" by default, red "STOP" while a turn runs. One control, both functions.
 */
class SendButton(private val onClick: ActionListener) : JComponent() {

    private val sendColor = JBColor(0x2E9E5B, 0x3FB873)
    private val stopColor = JBColor(0xD64545, 0xE5564E)
    private var hover = false

    var running: Boolean = false
        set(value) {
            if (field != value) { field = value; repaint() }
        }

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = Font(Font.SANS_SERIF, Font.BOLD, JBUI.scaleFontSize(13f))
        preferredSize = Dimension(JBUI.scale(96), JBUI.scale(30))
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mouseClicked(e: MouseEvent) { if (isEnabled) onClick.actionPerformed(null) }
        })
    }

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val base: Color = if (running) stopColor else sendColor
            g2.color = if (hover) base.brighter() else base
            val arc = JBUI.scale(8)
            g2.fillRoundRect(0, 0, width, height, arc, arc)

            g2.color = JBColor.WHITE
            g2.font = font
            val text = if (running) "STOP" else "Send"
            val fm = g2.fontMetrics
            val x = (width - fm.stringWidth(text)) / 2
            val y = (height - fm.height) / 2 + fm.ascent
            g2.drawString(text, x, y)
        } finally {
            g2.dispose()
        }
    }
}
