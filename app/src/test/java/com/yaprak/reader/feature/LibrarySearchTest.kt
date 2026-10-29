package com.yaprak.reader.feature

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.library.searchBooks
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class LibrarySearchTest {
    private val books = listOf(
        Book("1", "IŞIK ve İSTANBUL", "Çağrı Deniz", BookFormat.EPUB, "hidden.epub", "1.epub", 1, "1", 1),
        Book("2", "Quiet Morning", null, BookFormat.PDF, "deniz.pdf", "2.pdf", 1, "2", 2),
        Book("3", "İstanbul Hatıraları", "Deniz", BookFormat.EPUB, "3.epub", "3.epub", 1, "3", 3),
    )
    @Test fun `Turkish and English case matching is independent of system locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                assertEquals(listOf("1"), searchBooks(books, "ışık istanbul").map { it.id })
                assertEquals(listOf("2"), searchBooks(books, "QUIET MORNING").map { it.id })
            }
        } finally { Locale.setDefault(previous) }
    }
    @Test fun `all terms match across title and author while filenames are excluded`() {
        assertEquals(listOf("1", "3"), searchBooks(books, "  DENİZ\tistanbul\u00a0 ").map { it.id })
        assertTrue(searchBooks(books, "quiet deniz").isEmpty())
        assertTrue(searchBooks(books, "hidden.epub").isEmpty())
    }
    @Test fun `blank returns original ordering and searches never mutate the source`() {
        assertSame(books, searchBooks(books, " \t\n\u00a0"))
        searchBooks(books, "olmayan")
        assertEquals(listOf("1", "2", "3"), books.map { it.id })
    }
}
