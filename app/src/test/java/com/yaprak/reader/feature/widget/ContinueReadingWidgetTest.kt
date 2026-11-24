package com.yaprak.reader.feature.widget

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import org.junit.Assert.*
import org.junit.Test

class ContinueReadingWidgetTest {
    private fun book(id: String, lastReadAt: Long?) =
        Book(id, "Kitap $id", null, BookFormat.EPUB, "$id.epub", "$id.epub", 10, id.repeat(64).take(64), 1, lastReadAt = lastReadAt)

    @Test fun showsTheMostRecentlyReadBookWithItsProgress() {
        val books = listOf(book("a", 10), book("b", 30), book("c", null))
        val content = continueReadingWidgetContent(books, mapOf("b" to 42, "a" to 90))
        assertEquals("b", content.book?.id)
        assertEquals(42, content.percent)
        assertEquals("%42 okundu", content.percentText)
    }

    @Test fun nothingReadYetOrUnknownProgressShowsNoBookOrNoPercent() {
        assertNull(continueReadingWidgetContent(listOf(book("a", null)), emptyMap()).book)
        assertNull(continueReadingWidgetContent(emptyList(), emptyMap()).book)
        val noProgress = continueReadingWidgetContent(listOf(book("a", 5)), emptyMap())
        assertEquals("a", noProgress.book?.id); assertNull(noProgress.percentText)
        assertNull(ContinueReadingWidgetContent(book("a", 5), 140).percentText)
    }
}
