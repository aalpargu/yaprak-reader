package com.yaprak.reader.feature.vocabulary

import com.yaprak.reader.domain.vocabulary.SavedWord
import java.text.Collator
import java.util.Locale

internal enum class SavedWordSort {
    RECENT,
    ENGLISH,
    TURKISH,
}

/** Sorts a book's filtered words for display. Equal keys keep their supplied order. */
internal fun sortSavedWords(words: List<SavedWord>, sort: SavedWordSort): List<SavedWord> {
    if (sort == SavedWordSort.RECENT) return words
    val collator = Collator.getInstance(Locale.forLanguageTag("tr-TR")).apply {
        strength = Collator.SECONDARY
    }
    return words.sortedWith { first, second ->
        val firstKey = if (sort == SavedWordSort.ENGLISH) first.english else first.turkish
        val secondKey = if (sort == SavedWordSort.ENGLISH) second.english else second.turkish
        collator.compare(firstKey, secondKey)
    }
}
