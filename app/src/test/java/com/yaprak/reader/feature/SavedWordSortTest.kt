package com.yaprak.reader.feature

import com.yaprak.reader.domain.vocabulary.SavedWord
import com.yaprak.reader.feature.vocabulary.SavedWordSort
import com.yaprak.reader.feature.vocabulary.sortSavedWords
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedWordSortTest {
    private val words = listOf(
        SavedWord("d", "Daisy", "daha", 4),
        SavedWord("c2", "Çilek", "çilek", 3),
        SavedWord("c1", "Cedar", "cami", 2),
        SavedWord("a", "apple", "armut", 1),
    )

    @Test fun `recent preserves repository order without mutating supplied words`() {
        assertEquals(listOf("d", "c2", "c1", "a"), sortSavedWords(words, SavedWordSort.RECENT).map { it.id })
        assertEquals(listOf("d", "c2", "c1", "a"), words.map { it.id })
    }

    @Test fun `alphabetical English and Turkish sorting uses Turkish collation independent of device locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                assertEquals(listOf("a", "c1", "c2", "d"),
                    sortSavedWords(words, SavedWordSort.ENGLISH).map { it.id })
                assertEquals(listOf("a", "c1", "c2", "d"),
                    sortSavedWords(words, SavedWordSort.TURKISH).map { it.id })
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `equal sort values retain repository order and each mode uses its own field`() {
        val ties = listOf(
            SavedWord("first", "leaf", "zeytin", 3),
            SavedWord("second", "leaf", "armut", 2),
            SavedWord("third", "apple", "armut", 1),
        )
        assertEquals(listOf("third", "first", "second"),
            sortSavedWords(ties, SavedWordSort.ENGLISH).map { it.id })
        assertEquals(listOf("second", "third", "first"),
            sortSavedWords(ties, SavedWordSort.TURKISH).map { it.id })
        assertEquals(listOf("first", "second", "third"), ties.map { it.id })
    }
}
