package com.yaprak.reader.feature

import com.yaprak.reader.domain.notes.BookNote
import com.yaprak.reader.feature.notes.limitBookNoteSearchQuery
import com.yaprak.reader.feature.notes.searchBookNotes
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BookNoteSearchTest {
    private val notes = listOf(
        BookNote("1", "IŞIK ve gece\nİstanbul", 3),
        BookNote("2", "Sessiz bir sabah", 2),
        BookNote("3", "Işık altında sabah", 1),
    )

    @Test fun `Turkish I forms match independent of device locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                assertEquals(listOf("1", "3"), searchBookNotes(notes, "ışık").map { it.id })
                assertEquals(listOf("1"), searchBookNotes(notes, "IŞIK istanbul").map { it.id })
                assertEquals(listOf("1"), searchBookNotes(notes, "ışık İSTANBUL").map { it.id })
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `all whitespace separated terms match saved multiline text in supplied order`() {
        assertEquals(listOf("3"), searchBookNotes(notes, "  sabah\tışık\u00a0").map { it.id })
        assertEquals(listOf("1"), searchBookNotes(notes, "gece\nİstanbul").map { it.id })
        assertTrue(searchBookNotes(notes, "ışık sabah gece").isEmpty())
        assertTrue(searchBookNotes(notes, "yok").isEmpty())
        assertEquals(listOf("3", "1"),
            searchBookNotes(listOf(notes[2], notes[1], notes[0]), "ışık").map { it.id })
    }

    @Test fun `blank query returns original list and query cap respects UTF16 pairs`() {
        assertSame(notes, searchBookNotes(notes, " \t\n\u00a0"))
        assertEquals("x".repeat(200), limitBookNoteSearchQuery("x".repeat(201)))
        assertEquals("x".repeat(198) + "😀", limitBookNoteSearchQuery("x".repeat(198) + "😀y"))
        assertEquals("x".repeat(199), limitBookNoteSearchQuery("x".repeat(199) + "😀y"))
    }
}
