package com.yaprak.reader.feature

import com.yaprak.reader.domain.vocabulary.SavedWord
import com.yaprak.reader.feature.vocabulary.searchSavedWords
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedWordSearchTest {
    private val words = listOf(
        SavedWord("1", "Light", "IŞIK", 3),
        SavedWord("2", "Quiet morning", "Sakin sabah", 2),
        SavedWord("3", "City", "İstanbul şehri", 1),
    )

    @Test fun `Turkish and English matching is independent of device locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                assertEquals(listOf("1"), searchSavedWords(words, "LIGHT ışık").map { it.id })
                assertEquals(listOf("3"), searchSavedWords(words, "CITY istanbul").map { it.id })
                assertEquals(listOf("2"), searchSavedWords(words, "QUIET SABAH").map { it.id })
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `all query terms match combined English and Turkish fields in original order`() {
        assertEquals(listOf("1", "2", "3"), searchSavedWords(words, "i").map { it.id })
        assertEquals(listOf("2"), searchSavedWords(words, " sabah\tquiet\u00a0").map { it.id })
        assertTrue(searchSavedWords(words, "light sabah").isEmpty())
        assertTrue(searchSavedWords(words, "unlisted").isEmpty())
    }

    @Test fun `blank query returns supplied list and search does not mutate it`() {
        val supplied = listOf(words[2], words[0])
        assertSame(supplied, searchSavedWords(supplied, " \t\n\u00a0"))
        assertEquals(listOf("3", "1"), searchSavedWords(supplied, "i").map { it.id })
        assertTrue(searchSavedWords(supplied, "quiet").isEmpty())
        assertEquals(listOf("3", "1"), supplied.map { it.id })
    }
}
