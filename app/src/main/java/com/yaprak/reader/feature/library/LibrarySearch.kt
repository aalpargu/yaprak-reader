package com.yaprak.reader.feature.library

import com.yaprak.reader.domain.library.Book
import java.util.Locale

/** Matches all words across stored metadata, preserving the repository's ordering. */
internal fun searchBooks(books: List<Book>, query: String): List<Book> {
    val terms = query.searchKey().split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)
    if (terms.isEmpty()) return books
    return books.filter { book ->
        val text = buildString {
            append(book.title); append(' '); append(book.authors.orEmpty())
            append(' '); append(book.organization.series.orEmpty())
            append(' '); append(book.organization.genre.orEmpty())
            book.organization.tags.forEach { append(' '); append(it) }
            book.organization.collections.forEach { append(' '); append(it) }
        }.searchKey()
        terms.all(text::contains)
    }
}

// Match Turkish İ/I/ı/i and English titles regardless of the device locale.
internal fun String.searchKey() = lowercase(Locale.ROOT).replace("i\u0307", "i").replace('ı', 'i')
