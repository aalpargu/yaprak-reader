package com.yaprak.reader.feature.bookmarks

import com.yaprak.reader.domain.bookmarks.Bookmark
import java.text.Collator
import java.util.Locale

enum class BookmarkSort {
    RECORD_ORDER,
    TITLE_ASC,
    ;

    fun label(): String = when (this) {
        RECORD_ORDER -> "Kayıt sırası"
        TITLE_ASC -> "Başlık A–Z"
    }
}

/** Sorts only the supplied saved bookmarks for display; equal titles keep repository order. */
internal fun sortBookmarks(entries: List<Bookmark>, sort: BookmarkSort): List<Bookmark> {
    if (sort == BookmarkSort.RECORD_ORDER) return entries
    val collator = Collator.getInstance(Locale.forLanguageTag("tr-TR")).apply {
        strength = Collator.SECONDARY
    }
    return entries.sortedWith { first, second -> collator.compare(first.target.label, second.target.label) }
}
