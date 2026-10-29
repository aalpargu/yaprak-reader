package com.yaprak.reader.feature.library

import com.yaprak.reader.domain.library.Book
import java.text.Collator
import java.util.Locale

typealias LibrarySort = com.yaprak.reader.domain.library.LibrarySort

/** Sort a filtered view without changing the repository's canonical book order. */
internal fun sortBooks(books: List<Book>, sort: LibrarySort): List<Book> {
    if (sort == LibrarySort.RECENT) return books
    val byRecent = compareByDescending<Book> { it.importedAt }.thenByDescending { it.id }
    val byLastRead = compareByDescending<Book> { it.lastReadAt }.then(byRecent)
    val collator = Collator.getInstance(Locale.forLanguageTag("tr-TR")).apply {
        strength = Collator.SECONDARY
    }
    val byTitle = Comparator<Book> { first, second -> collator.compare(first.title, second.title) }
    val comparator = when (sort) {
        LibrarySort.RECENT -> byRecent
        LibrarySort.LAST_READ -> byLastRead
        LibrarySort.TITLE -> byTitle.then(byRecent)
        LibrarySort.AUTHOR -> Comparator<Book> { first, second ->
            val left = first.authors?.takeIf(String::isNotBlank)
            val right = second.authors?.takeIf(String::isNotBlank)
            when {
                left == null && right == null -> 0
                left == null -> 1
                right == null -> -1
                else -> collator.compare(left, right)
            }
        }.then(byTitle).then(byRecent)
    }
    return books.sortedWith(comparator)
}
