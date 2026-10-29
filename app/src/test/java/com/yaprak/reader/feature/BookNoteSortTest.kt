package com.yaprak.reader.feature

import com.yaprak.reader.domain.notes.BookNote
import com.yaprak.reader.feature.notes.BookNoteSort
import com.yaprak.reader.feature.notes.sortBookNotes
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BookNoteSortTest {
    private val notes = listOf(
        BookNote("z", "Zeytin", 5),
        BookNote("dotted", "İnci", 4),
        BookNote("cedilla", "Çınar", 3),
        BookNote("dotless", "Ihlamur", 2),
        BookNote("e", "Elma", 1),
    )

    @Test fun `record order returns original saved list`() {
        assertSame(notes, sortBookNotes(notes, BookNoteSort.RECORD_ORDER))
    }

    @Test fun `text ascending follows Turkish collation independent of device locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                assertEquals(listOf("cedilla", "e", "dotless", "dotted", "z"),
                    sortBookNotes(notes, BookNoteSort.TEXT_ASC).map { it.id })
            }
        } finally { Locale.setDefault(previous) }
        assertEquals(listOf("z", "dotted", "cedilla", "dotless", "e"), notes.map { it.id })
    }

    @Test fun `equal text keys retain repository order`() {
        val supplied = listOf(
            BookNote("third", "ada", 3),
            BookNote("other", "Bahar", 2),
            BookNote("first", "Ada", 1),
        )
        assertEquals(listOf("third", "first", "other"),
            sortBookNotes(supplied, BookNoteSort.TEXT_ASC).map { it.id })
    }
}
