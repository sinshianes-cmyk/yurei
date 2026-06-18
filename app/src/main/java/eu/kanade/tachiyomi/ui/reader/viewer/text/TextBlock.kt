package eu.kanade.tachiyomi.ui.reader.viewer.text

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

sealed interface TextBlock {
    data class Paragraph(val html: String) : TextBlock
    data class Heading(val html: String, val level: Int) : TextBlock
    data class Image(val url: String) : TextBlock
    data object Rule : TextBlock
}

object TextChapterParser {

    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val CONTAINERS = setOf("div", "section", "article", "main")

    fun parse(html: String): List<TextBlock> {
        if (html.isBlank()) return emptyList()
        val blocks = mutableListOf<TextBlock>()
        collect(Jsoup.parseBodyFragment(html).body(), blocks)
        return blocks
    }

    private fun collect(parent: Element, out: MutableList<TextBlock>) {
        for (node in parent.childNodes()) {
            when (node) {
                is TextNode -> if (node.text().isNotBlank()) {
                    out += TextBlock.Paragraph(node.text().trim())
                }
                is Element -> collectElement(node, out)
                else -> Unit
            }
        }
    }

    private fun collectElement(el: Element, out: MutableList<TextBlock>) {
        when (val tag = el.normalName()) {
            in HEADINGS -> if (el.text().isNotBlank()) {
                out += TextBlock.Heading(el.html(), tag.last().digitToInt())
            }
            "hr" -> out += TextBlock.Rule
            "img" -> el.attr("src").takeIf { it.isNotBlank() }?.let { out += TextBlock.Image(it) }
            in CONTAINERS -> if (el.hasBlockChildren()) collect(el, out) else el.addAsParagraph(out)
            else -> el.addAsParagraph(out)
        }
    }

    private fun Element.addAsParagraph(out: MutableList<TextBlock>) {
        if (text().isNotBlank() || selectFirst("img") != null) {
            out += TextBlock.Paragraph(html())
        }
    }

    private fun Element.hasBlockChildren(): Boolean = children().any { child ->
        val tag = child.normalName()
        tag in HEADINGS || tag in CONTAINERS || tag == "p" || tag == "blockquote" || tag == "hr"
    }
}
