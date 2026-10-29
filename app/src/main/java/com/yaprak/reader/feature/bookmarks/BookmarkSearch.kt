package com.yaprak.reader.feature.bookmarks

import com.yaprak.reader.domain.bookmarks.Bookmark
import java.util.Locale

internal const val MAX_BOOKMARK_SEARCH_LENGTH = 200

/** Filters only saved bookmark titles, preserving the supplied repository order. */
internal fun searchBookmarks(entries: List<Bookmark>, query: String): List<Bookmark> {
    val terms = query.searchKey().split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)
    if (terms.isEmpty()) return entries
    return entries.filter { entry ->
        val title = entry.target.label.searchKey()
        terms.all(title::contains)
    }
}

// Match Turkish İ/I/ı/i independently of the device's default locale.
private fun String.searchKey() = lowercase(Locale.ROOT).replace("i\u0307", "i").replace('ı', 'i')

/** A 200 UTF-16-unit cap which never splits a surrogate pair. */
internal fun limitBookmarkSearchQuery(query: String): String {
    if (query.length <= MAX_BOOKMARK_SEARCH_LENGTH) return query
    val limit = MAX_BOOKMARK_SEARCH_LENGTH
    val safeLimit = if (Character.isHighSurrogate(query[limit - 1]) &&
        Character.isLowSurrogate(query[limit])) limit - 1 else limit
    return query.take(safeLimit)
}
