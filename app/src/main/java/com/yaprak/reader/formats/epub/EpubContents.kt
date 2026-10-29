package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.reader.BookContents
import com.yaprak.reader.domain.reader.ContentsEntry
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.RelativeUrl

/** Maps Readium's already parsed TOC on the loader's IO dispatcher, without parsing EPUB XML. */
internal class EpubContents(links: List<Link>, readingOrder: List<Link>) {
    val contents: BookContents
    private val destinations = mutableMapOf<String, Link>()
    private val resourceCounts = readingOrder.groupingBy { it.url().removeFragment().toString() }.eachCount()
    private val wholeResourceEntries: Map<String, String>

    init {
        val resources = readingOrder.map { it.url().removeFragment() }.toSet()
        val entries = mutableListOf<ContentsEntry>()
        // Iterators avoid recursion and avoid copying an unbounded list of pending children.
        val stack = ArrayDeque<Iterator<Link>>()
        stack.addLast(links.iterator())
        while (stack.isNotEmpty() && entries.size < MAX_ENTRIES) {
            if (!stack.last().hasNext()) { stack.removeLast(); continue }
            val link = stack.last().next()
            val url = link.url()
            val allowed = url is RelativeUrl && !url.toString().startsWith("//") && url.removeFragment() in resources
            val id = entries.size.toString()
            val title = link.title?.take(500)?.trim()?.takeIf { it.isNotBlank() } ?: "Adsız başlık"
            entries += ContentsEntry(id, title, stack.size - 1, allowed)
            if (allowed) destinations[id] = link
            if (link.children.isNotEmpty()) stack.addLast(link.children.iterator())
        }
        contents = BookContents(entries.toList(), truncated = stack.any { it.hasNext() })
        val order = readingOrder.map { it.url().removeFragment().toString() }
        val ordinals = order.withIndex().associate { it.value to it.index }
        val tocOrdinals = entries.mapNotNull { entry -> destinations[entry.id]?.let {
            ordinals[it.url().removeFragment().toString()]
        } }
        val ordered = tocOrdinals.zipWithNext().all { (a, b) -> a <= b }
        wholeResourceEntries = if (contents.truncated || !ordered || resourceCounts.values.any { it != 1 }) emptyMap()
            else destinations.entries.groupBy { it.value.url().removeFragment().toString() }
                .mapNotNull { (href, matches) ->
                    val actual = matches.singleOrNull() ?: return@mapNotNull null
                    if (actual.value.url().toString() != href) return@mapNotNull null
                    val entryIndex = actual.key.toInt()
                    val entry = entries[entryIndex]
                    val next = entries.getOrNull(entryIndex + 1)
                    if (next != null && next.depth > entry.depth) return@mapNotNull null
                    val resourceIndex = ordinals[href] ?: return@mapNotNull null
                    // A chapter may continue into unlisted resources. Only admit an exact adjacent
                    // next TOC boundary (or the actual end of the reading order), never a file guess.
                    val exactEnd = if (resourceIndex == order.lastIndex) next == null
                        else next != null && destinations[next.id]?.url()?.toString() == order[resourceIndex + 1]
                    if (exactEnd) href to actual.key else null
                }.toMap()
    }

    /** Keep the original fragment and media type; Navigator resolves the link within the publication. */
    fun destination(entryId: String): Link? = destinations[entryId]

    /** Exact leaf TOC start and next-resource/EOF boundary; multi-resource/fragment chapters are unavailable. */
    fun wholeResourceEntryId(href: String): String? = wholeResourceEntries[href]

    fun entryIdForResource(href: String): String? = if (contents.truncated || resourceCounts[href] != 1) null
        else destinations.entries.firstOrNull { it.value.url().removeFragment().toString() == href }?.key

    private companion object { const val MAX_ENTRIES = 5000 }
}
