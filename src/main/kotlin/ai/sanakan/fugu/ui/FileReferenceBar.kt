package ai.sanakan.fugu.ui

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * A row of removable chips for files attached to the next message (added via the
 * `@` picker or drag-and-drop). Hidden when empty.
 */
class FileReferenceBar(private val onChange: () -> Unit) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))) {

    private val files = LinkedHashSet<VirtualFile>()

    init {
        isOpaque = false
        isVisible = false
    }

    fun addFile(file: VirtualFile) {
        if (files.add(file)) rebuild()
    }

    fun files(): List<VirtualFile> = files.toList()

    fun clear() {
        if (files.isNotEmpty()) {
            files.clear()
            rebuild()
        }
    }

    private fun rebuild() {
        removeAll()
        files.forEach { add(chip(it)) }
        isVisible = files.isNotEmpty()
        revalidate()
        repaint()
        onChange()
    }

    private fun chip(file: VirtualFile): JPanel {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(2, 6),
            )
        }
        chip.add(JBLabel(file.fileType.icon))
        chip.add(JBLabel(file.name))
        chip.add(JBLabel("✕").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.BOLD)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Remove"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    files.remove(file)
                    rebuild()
                }
            })
        })
        chip.toolTipText = file.path
        return chip
    }
}
