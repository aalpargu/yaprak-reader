package com.yaprak.reader.formats.fb2

import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class Fb2TextPart(val index: Int, val href: String, val title: String, val text: String, val sha256: String)

/** Complete original body text, with extraction separators; never generated XHTML or DOM offsets. */
internal suspend fun fb2OriginalText(document: Fb2Document): List<Fb2TextPart> {
    val caller = currentCoroutineContext()
    val blocks = setOf("section", "title", "p", "empty-line", "epigraph", "cite", "poem", "stanza", "v",
        "text-author", "subtitle", "table", "tr", "th", "td", "annotation")
    return fb2ViewingParts(document).mapIndexed { index, part ->
        caller.ensureActive()
        val out = StringBuilder()
        fun append(node: Fb2Content) {
            caller.ensureActive()
            when (node) {
                is Fb2Text -> out.append(node.value)
                is Fb2Element -> {
                    if (node.name in blocks) out.append('\n')
                    node.children.forEach(::append)
                    if (node.name in blocks) out.append('\n')
                }
            }
        }
        part.nodes.forEach(::append)
        // XML source indentation is not a visible offset. Keep block boundaries for snippets.
        val normalized = StringBuilder(out.length)
        var lineBreak = false
        var space = false
        out.forEachIndexed { offset, char ->
            if (offset % 4096 == 0) caller.ensureActive()
            when {
                char == '\n' || char == '\r' -> lineBreak = true
                char.isWhitespace() -> space = true
                else -> {
                    if (normalized.isNotEmpty()) {
                        if (lineBreak) normalized.append('\n') else if (space) normalized.append(' ')
                    }
                    normalized.append(char); lineBreak = false; space = false
                }
            }
        }
        val text = normalized.toString()
        val section = part.nodes.filterIsInstance<Fb2Element>().firstOrNull { it.name == "section" }
        val label = (section ?: part.body).elements.firstOrNull { it.name == "title" }?.text()
            ?.replace(Regex("[\\s\\p{Z}]+"), " ")?.trim()?.takeIf(String::isNotEmpty) ?: "Metin ${index + 1}"
        val title = label.substring(0, label.offsetByCodePoints(0, minOf(180, label.codePointCount(0, label.length))))
        caller.ensureActive()
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        Fb2TextPart(index, part.href, title, text, hash)
    }
}
