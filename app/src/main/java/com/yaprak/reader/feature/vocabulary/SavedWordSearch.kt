package com.yaprak.reader.feature.vocabulary

import com.yaprak.reader.domain.vocabulary.SavedWord
import java.util.Locale

/** Filters only the supplied book's words and preserves their saved ordering. */
internal fun searchSavedWords(words: List<SavedWord>, query: String): List<SavedWord> {
    val terms = query.searchKey().split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)
    if (terms.isEmpty()) return words
    return words.filter { word ->
        val text = "${word.english} ${word.turkish}".searchKey()
        terms.all(text::contains)
    }
}

// Keep the same locale-independent Turkish İ/I/ı/i matching as library search.
private fun String.searchKey() = lowercase(Locale.ROOT).replace("i\u0307", "i").replace('ı', 'i')
