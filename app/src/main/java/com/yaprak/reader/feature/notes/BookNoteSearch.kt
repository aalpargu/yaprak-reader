package com.yaprak.reader.feature.notes

import com.yaprak.reader.domain.notes.BookNote
import java.util.Locale

internal const val MAX_BOOK_NOTE_SEARCH_LENGTH = 200

/** Filters the supplied book's saved notes without changing repository order. */
internal fun searchBookNotes(entries: List<BookNote>, query: String): List<BookNote> {
    val terms = query.searchKey().split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)
    if (terms.isEmpty()) return entries
    return entries.filter { note ->
        val text = note.text.searchKey()
        terms.all(text::contains)
    }
}

// Match Turkish İ/I/ı/i independently of the device's default locale.
private fun String.searchKey() = lowercase(Locale.ROOT).replace("i\u0307", "i").replace('ı', 'i')

internal fun limitBookNoteSearchQuery(query: String): String {
    if (query.length <= MAX_BOOK_NOTE_SEARCH_LENGTH) return query
    val limit = MAX_BOOK_NOTE_SEARCH_LENGTH
    val safeLimit = if (Character.isHighSurrogate(query[limit - 1]) &&
        Character.isLowSurrogate(query[limit])) limit - 1 else limit
    return query.take(safeLimit)
}
