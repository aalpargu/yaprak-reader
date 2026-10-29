package com.yaprak.reader.feature

import com.yaprak.reader.domain.bookmarks.Bookmark
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.bookmarks.BookmarkSort
import com.yaprak.reader.feature.bookmarks.sortBookmarks
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BookmarkSortTest {
    private val bookmarks = listOf(
        bookmark("z", "Zeytin"),
        bookmark("dotted", "İnci"),
        bookmark("cedilla", "Çınar"),
        bookmark("dotless", "Ihlamur"),
        bookmark("e", "Elma"),
    )

    @Test fun `record order returns original saved list`() {
        assertSame(bookmarks, sortBookmarks(bookmarks, BookmarkSort.RECORD_ORDER))
    }

    @Test fun `title ascending follows Turkish collation independent of device locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                assertEquals(listOf("cedilla", "e", "dotless", "dotted", "z"),
                    sortBookmarks(bookmarks, BookmarkSort.TITLE_ASC).map { it.id })
            }
        } finally { Locale.setDefault(previous) }
        assertEquals(listOf("z", "dotted", "cedilla", "dotless", "e"), bookmarks.map { it.id })
    }

    @Test fun `equal title keys retain repository order`() {
        val supplied = listOf(
            bookmark("third", "ada"),
            bookmark("other", "Bahar"),
            bookmark("first", "Ada"),
        )
        assertEquals(listOf("third", "first", "other"),
            sortBookmarks(supplied, BookmarkSort.TITLE_ASC).map { it.id })
    }

    private fun bookmark(id: String, title: String) =
        Bookmark(id, BookmarkTarget(title, ReadingLocation("locator-$id", "$id.xhtml")), 1)
}
