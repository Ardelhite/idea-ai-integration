package ai.sanakan.fugu.ui

import ai.sanakan.fugu.cli.ApprovalDecision
import ai.sanakan.fugu.cli.ApprovalRequest
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Inline approval prompt (not a popup): shows the action the agent wants to take
 * with Approve / Approve for session / Decline buttons, rendered above the
 * composer just like [UserPromptPanel].
 */
class ApprovalPanel(
    request: ApprovalRequest,
    private val onDecision: (ApprovalDecision) -> Unit,
) : JPanel(BorderLayout()) {

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
        content.add(left(JBLabel("Approval needed").apply {
            foreground = JBColor(0xD9A40E, 0xF2C94C)
            font = font.deriveFont(Font.BOLD, font.size - 1f)
        }))
        content.add(left(JBLabel(request.summary).apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.emptyTop(2)
        }))
        request.detail?.takeIf { it.isNotBlank() }?.let {
            content.add(left(JBLabel("<html>${escape(it)}</html>").apply { foreground = JBColor.GRAY }))
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        buttons.add(JButton("Decline").apply { addActionListener { onDecision(ApprovalDecision.DECLINE) } })
        buttons.add(JButton("Approve for session").apply { addActionListener { onDecision(ApprovalDecision.ACCEPT_FOR_SESSION) } })
        buttons.add(JButton("Approve").apply { addActionListener { onDecision(ApprovalDecision.ACCEPT) } })

        add(content, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }

    private fun <T : Component> left(c: T): T =
        c.apply { (this as? javax.swing.JComponent)?.alignmentX = Component.LEFT_ALIGNMENT }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
