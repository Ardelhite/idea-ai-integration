package ai.sanakan.fugu.ui

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Converts Markdown to an HTML fragment using JetBrains' Markdown parser (GitHub
 * flavour, so fenced code blocks / tables / strikethrough render). The fragment
 * is embedded into a styled document by [MessageComponent].
 */
object MarkdownRenderer {

    private val flavour = GFMFlavourDescriptor()

    fun toHtmlBody(markdown: String): String {
        if (markdown.isBlank()) return ""
        return try {
            val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
            HtmlGenerator(markdown, tree, flavour)
                .generateHtml()
                .removePrefix("<body>")
                .removeSuffix("</body>")
        } catch (t: Throwable) {
            // Never let a parse hiccup (e.g. mid-stream partial markdown) break the UI.
            escape(markdown).replace("\n", "<br>")
        }
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
