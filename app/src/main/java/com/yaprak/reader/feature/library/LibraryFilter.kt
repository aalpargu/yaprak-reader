package com.yaprak.reader.feature.library

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookReadingStatus

enum class LibraryFilter { ALL, EPUB, PDF, TXT, CBZ, CBR, FB2, DOCX, NO_POSITION }

/** Independent, temporary filters. A selection in every populated field must match. */
data class LibraryOrganizationFilters(
    val author: String? = null,
    val series: String? = null,
    val genre: String? = null,
    val tag: String? = null,
    val collection: String? = null,
    val readingStatus: BookReadingStatus? = null,
    val favoritesOnly: Boolean = false,
) {
    val activeCount: Int get() = listOf(author, series, genre, tag, collection, readingStatus)
        .count { it != null } + if (favoritesOnly) 1 else 0
    val isActive: Boolean get() = activeCount > 0

    internal fun savedValues(): ArrayList<String> = arrayListOf(
        author.orEmpty(), series.orEmpty(), genre.orEmpty(), tag.orEmpty(), collection.orEmpty(),
        readingStatus?.name.orEmpty(), favoritesOnly.toString(),
    )

    internal companion object {
        fun fromSavedValues(values: List<String>): LibraryOrganizationFilters {
            fun field(index: Int): String? = values.getOrNull(index)?.trim()?.ifBlank { null }
            return LibraryOrganizationFilters(field(0), field(1), field(2), field(3), field(4),
                BookReadingStatus.entries.firstOrNull { it.name == field(5) }, values.getOrNull(6) == "true")
        }
    }
}

internal fun filterOrganizationBooks(books: List<Book>, filters: LibraryOrganizationFilters): List<Book> {
    if (!filters.isActive) return books
    fun matches(value: String?, selected: String?): Boolean = selected == null ||
        value?.searchKey() == selected.searchKey()
    return books.filter { book ->
        val organization = book.organization
        matches(book.authors, filters.author) && matches(organization.series, filters.series) &&
            matches(organization.genre, filters.genre) &&
            (filters.tag == null || organization.tags.any { matches(it, filters.tag) }) &&
            (filters.collection == null || organization.collections.any { matches(it, filters.collection) }) &&
            (filters.readingStatus == null || organization.readingStatus == filters.readingStatus) &&
            (!filters.favoritesOnly || organization.favorite)
    }
}

/** Filter the current repository snapshot without changing book order or stored data. */
internal fun filterBooks(books: List<Book>, filter: LibraryFilter): List<Book> = when (filter) {
    LibraryFilter.ALL -> books
    LibraryFilter.EPUB -> books.filter { it.format == BookFormat.EPUB }
    LibraryFilter.PDF -> books.filter { it.format == BookFormat.PDF }
    LibraryFilter.TXT -> books.filter { it.format == BookFormat.TXT }
    LibraryFilter.CBZ -> books.filter { it.format == BookFormat.CBZ }
    LibraryFilter.CBR -> books.filter { it.format == BookFormat.CBR }
    LibraryFilter.FB2 -> books.filter { it.format == BookFormat.FB2 }
    LibraryFilter.DOCX -> books.filter { it.format == BookFormat.DOCX }
    // lastReadAt is scoped to the book's current content hash; null does not prove it was never opened.
    LibraryFilter.NO_POSITION -> books.filter { it.lastReadAt == null }
}
