package com.yaprak.reader.feature

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.library.LibraryFilter
import com.yaprak.reader.feature.library.filterBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryFilterTest {
    private fun book(id: String, format: BookFormat, lastReadAt: Long? = null) =
        Book(id, id, null, format, "$id.${format.extension}", "$id.${format.extension}", 1, id, 1,
            lastReadAt = lastReadAt)

    @Test fun `all returns the unchanged source list`() {
        val books = listOf(book("pdf", BookFormat.PDF), book("epub", BookFormat.EPUB, 5))

        assertSame(books, filterBooks(books, LibraryFilter.ALL))
        assertEquals(listOf("pdf", "epub"), books.map(Book::id))
    }

    @Test fun `format filters preserve the input order within each format`() {
        val books = listOf(
            book("pdf-first", BookFormat.PDF),
            book("epub-first", BookFormat.EPUB, 3),
            book("pdf-second", BookFormat.PDF, 4),
            book("epub-second", BookFormat.EPUB),
        )

        assertEquals(listOf("epub-first", "epub-second"),
            filterBooks(books, LibraryFilter.EPUB).map(Book::id))
        assertEquals(listOf("pdf-first", "pdf-second"),
            filterBooks(books, LibraryFilter.PDF).map(Book::id))
        assertEquals(listOf("pdf-first", "epub-first", "pdf-second", "epub-second"), books.map(Book::id))
    }

    @Test fun `no position includes both formats only when current position is absent`() {
        val books = listOf(
            book("pdf-no-position", BookFormat.PDF),
            book("epub-with-position", BookFormat.EPUB, 12),
            book("epub-no-position", BookFormat.EPUB),
            book("pdf-with-position", BookFormat.PDF, 0),
        )

        assertEquals(listOf("pdf-no-position", "epub-no-position"),
            filterBooks(books, LibraryFilter.NO_POSITION).map(Book::id))
        assertEquals(4, books.size)
    }

    @Test fun `filters handle an empty library`() {
        for (filter in LibraryFilter.entries) {
            assertEquals(emptyList<Book>(), filterBooks(emptyList(), filter))
        }
    }

    @Test fun txtHasItsOwnStableFilterAndRemainsEligibleForContinueReadingAndMetadataSearch() {
        val first = book("txt-first", BookFormat.TXT)
        val second = book("txt-second", BookFormat.TXT, 3)
        val mixed = listOf(book("epub", BookFormat.EPUB), first, book("pdf", BookFormat.PDF), second)
        assertEquals(listOf(first, second), filterBooks(mixed, LibraryFilter.TXT))
        assertSame(mixed, filterBooks(mixed, LibraryFilter.ALL))
        assertEquals(listOf("epub", "txt-first", "pdf"), filterBooks(mixed, LibraryFilter.NO_POSITION).map(Book::id))
    }
    @Test fun cbzFilterPreservesOriginalBookOrderAndOtherFormatSelections() {
        val one = book("comic-one", BookFormat.CBZ); val two = book("comic-two", BookFormat.CBZ, 3)
        val mixed = listOf(book("txt", BookFormat.TXT), one, book("epub", BookFormat.EPUB), two)
        assertEquals(listOf(one, two), filterBooks(mixed, LibraryFilter.CBZ))
        assertEquals(listOf("txt"), filterBooks(mixed, LibraryFilter.TXT).map(Book::id))
        assertEquals(listOf("txt", "comic-one", "epub"), filterBooks(mixed, LibraryFilter.NO_POSITION).map(Book::id))
    }
}
