package com.yaprak.reader.feature

import com.yaprak.reader.domain.bookmarks.Bookmark
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.bookmarks.limitBookmarkSearchQuery
import com.yaprak.reader.feature.bookmarks.searchBookmarks
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkSearchTest {
    private val entries = listOf(
        bookmark("1", "IŞIK ve gece İstanbul", "one.xhtml"),
        bookmark("2", "Sessiz bir sabah", "two.xhtml"),
        bookmark("3", "Işık altında sabah", "three.xhtml"),
    )

    @Test fun `Turkish I forms match regardless of device locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                assertEquals(listOf("1", "3"), searchBookmarks(entries, "ışık").map { it.id })
                assertEquals(listOf("1"), searchBookmarks(entries, "IŞIK istanbul").map { it.id })
                assertEquals(listOf("1"), searchBookmarks(entries, "ışık İSTANBUL").map { it.id })
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `all whitespace separated terms match only saved titles in repository order`() {
        assertEquals(listOf("3"), searchBookmarks(entries, "  sabah\tışık\u00a0").map { it.id })
        assertEquals(listOf("1"), searchBookmarks(entries, "gece\nİstanbul").map { it.id })
        assertEquals(listOf("3", "1"), searchBookmarks(listOf(entries[2], entries[1], entries[0]), "ışık").map { it.id })
        assertTrue(searchBookmarks(entries, "ışık sabah gece").isEmpty())
        assertTrue(searchBookmarks(listOf(bookmark("4", "Başlık", "hidden-search-term.xhtml")), "hidden-search-term").isEmpty())
    }

    @Test fun `blank query returns original list and cap never splits UTF16 surrogate pair`() {
        assertSame(entries, searchBookmarks(entries, " \t\n\u00a0"))
        assertEquals("x".repeat(200), limitBookmarkSearchQuery("x".repeat(201)))
        assertEquals("x".repeat(198) + "😀", limitBookmarkSearchQuery("x".repeat(198) + "😀y"))
        assertEquals("x".repeat(199), limitBookmarkSearchQuery("x".repeat(199) + "😀y"))
    }

    private fun bookmark(id: String, label: String, href: String) =
        Bookmark(id, BookmarkTarget(label, ReadingLocation("locator-$id", href)), id.toLong())
}
