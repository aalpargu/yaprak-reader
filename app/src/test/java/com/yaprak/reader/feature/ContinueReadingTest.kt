package com.yaprak.reader.feature

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.library.mostRecentlyReadBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueReadingTest {
    private fun book(id: String, importedAt: Long, lastReadAt: Long? = null) =
        Book(id, id, null, BookFormat.EPUB, "$id.epub", "$id.epub", 1, id,
            importedAt, lastReadAt = lastReadAt)

    @Test fun `most recent saved reading wins over newly imported unread books`() {
        val books = listOf(book("unread", 40), book("old", 10, 200), book("new", 20, 200),
            book("earlier", 30, 100))
        assertEquals("new", mostRecentlyReadBook(books)?.id)
        assertEquals("new", mostRecentlyReadBook(books.reversed())?.id)
    }

    @Test fun `no reading history does not invent a continue target`() {
        assertNull(mostRecentlyReadBook(emptyList()))
        assertNull(mostRecentlyReadBook(listOf(book("one", 1), book("two", 2))))
    }
}
