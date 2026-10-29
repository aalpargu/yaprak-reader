package com.yaprak.reader.feature.notes

import com.yaprak.reader.domain.notes.BookNote
import java.text.Collator
import java.util.Locale

internal enum class BookNoteSort {
    RECORD_ORDER,
    TEXT_ASC,
    ;

    fun label(): String = when (this) {
        RECORD_ORDER -> "Kayıt sırası"
        TEXT_ASC -> "Not metni A–Z"
    }
}

/** Sorts only the supplied saved notes for display; equal text keeps repository order. */
internal fun sortBookNotes(entries: List<BookNote>, sort: BookNoteSort): List<BookNote> {
    if (sort == BookNoteSort.RECORD_ORDER) return entries
    val collator = Collator.getInstance(Locale.forLanguageTag("tr-TR")).apply {
        strength = Collator.SECONDARY
    }
    return entries.sortedWith { first, second -> collator.compare(first.text, second.text) }
}
