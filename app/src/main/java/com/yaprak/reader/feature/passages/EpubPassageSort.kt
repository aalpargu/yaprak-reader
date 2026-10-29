package com.yaprak.reader.feature.passages

import com.yaprak.reader.domain.passages.EpubPassage
import java.text.Collator
import java.util.Locale

internal enum class EpubPassageSort {
    RECORD_ORDER,
    TEXT_ASC,
    ;

    fun label(): String = when (this) {
        RECORD_ORDER -> "Kayıt sırası"
        TEXT_ASC -> "Pasaj metni A–Z"
    }
}

/** Sorts only saved passages for display; equal text keeps repository order. */
internal fun sortEpubPassages(entries: List<EpubPassage>, sort: EpubPassageSort): List<EpubPassage> {
    if (sort == EpubPassageSort.RECORD_ORDER) return entries
    val collator = Collator.getInstance(Locale.forLanguageTag("tr-TR")).apply {
        strength = Collator.SECONDARY
    }
    return entries.sortedWith { first, second -> collator.compare(first.text, second.text) }
}
