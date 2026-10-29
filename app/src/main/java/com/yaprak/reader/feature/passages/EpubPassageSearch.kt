package com.yaprak.reader.feature.passages

import com.yaprak.reader.domain.passages.EpubPassage
import java.util.Locale

internal const val MAX_EPUB_PASSAGE_SEARCH_LENGTH = 200

/** Filters only the supplied book's saved text without changing repository order. */
internal fun searchEpubPassages(entries: List<EpubPassage>, query: String): List<EpubPassage> {
    val terms = query.searchKey().split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)
    if (terms.isEmpty()) return entries
    return entries.filter { passage ->
        val text = (passage.text + "\n" + (passage.attachedNote ?: "")).searchKey()
        terms.all(text::contains)
    }
}

// Match Turkish İ/I/ı/i independently of the device's default locale.
private fun String.searchKey() = lowercase(Locale.ROOT).replace("i\u0307", "i").replace('ı', 'i')

internal fun limitEpubPassageSearchQuery(query: String): String {
    if (query.length <= MAX_EPUB_PASSAGE_SEARCH_LENGTH) return query
    val limit = MAX_EPUB_PASSAGE_SEARCH_LENGTH
    val safeLimit = if (Character.isHighSurrogate(query[limit - 1]) &&
        Character.isLowSurrogate(query[limit])) limit - 1 else limit
    return query.take(safeLimit)
}
