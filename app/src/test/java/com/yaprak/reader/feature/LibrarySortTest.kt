package com.yaprak.reader.feature

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.library.LibrarySort
import com.yaprak.reader.feature.library.sortBooks
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LibrarySortTest {
    private fun book(id: String, title: String, author: String?, importedAt: Long = 1, lastReadAt: Long? = null) =
        Book(id, title, author, BookFormat.EPUB, "$id.epub", "$id.epub", 1, id, importedAt, lastReadAt = lastReadAt)

    @Test fun `Turkish title and author order ignores device locale`() {
        val books = listOf(
            book("i", "İzmir", "İpek"),
            book("c", "Çiçek", "Çınar"),
            book("a", "Ankara", "Ada"),
            book("u", "Uludağ", null),
            book("d", "Duru", "Deniz"),
        )
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                assertEquals(listOf("a", "c", "d", "i", "u"), sortBooks(books, LibrarySort.TITLE).map(Book::id))
                assertEquals(listOf("a", "c", "d", "i", "u"), sortBooks(books, LibrarySort.AUTHOR).map(Book::id))
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `unknown author follows named authors and ties have stable recent order`() {
        val books = listOf(
            book("old", "A", "Deniz", 1),
            book("unknown", "B", null, 10),
            book("new", "A", "deniz", 2),
        )
        assertEquals(listOf("new", "old", "unknown"), sortBooks(books, LibrarySort.AUTHOR).map(Book::id))
        assertEquals(listOf("new", "old", "unknown"), sortBooks(books, LibrarySort.TITLE).map(Book::id))
        assertEquals(listOf("old", "unknown", "new"), books.map(Book::id))
        assertSame(books, sortBooks(books, LibrarySort.RECENT))
    }

    @Test fun `last read puts unread books last and uses imported order for ties`() {
        val books = listOf(
            book("unread-new", "A", null, importedAt = 40),
            book("read-old", "B", null, importedAt = 10, lastReadAt = 100),
            book("read-new", "C", null, importedAt = 20, lastReadAt = 100),
            book("unread-old", "D", null, importedAt = 30),
            book("latest", "E", null, importedAt = 1, lastReadAt = 200),
        )
        assertEquals(listOf("latest", "read-new", "read-old", "unread-new", "unread-old"),
            sortBooks(books, LibrarySort.LAST_READ).map(Book::id))
        assertEquals(listOf("unread-new", "read-old", "read-new", "unread-old", "latest"),
            books.map(Book::id))
    }
}
