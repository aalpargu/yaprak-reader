package com.yaprak.reader.feature

import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.feature.passages.EpubPassageSort
import com.yaprak.reader.feature.passages.sortEpubPassages
import com.yaprak.reader.reader.SourceTextAnchor
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubPassageSortTest {
    private val anchor = SourceTextAnchor("chapter.xhtml", 1, 5, "a".repeat(64))
    private val passages = listOf(
        EpubPassage("z", "Zeytin", anchor, 5),
        EpubPassage("dotted", "İnci", anchor, 4),
        EpubPassage("cedilla", "Çınar", anchor, 3),
        EpubPassage("dotless", "Ihlamur", anchor, 2),
        EpubPassage("e", "Elma", anchor, 1),
    )

    @Test fun `record order returns original list and labels describe presentation`() {
        assertSame(passages, sortEpubPassages(passages, EpubPassageSort.RECORD_ORDER))
        assertEquals("Kayıt sırası", EpubPassageSort.RECORD_ORDER.label())
        assertEquals("Pasaj metni A–Z", EpubPassageSort.TEXT_ASC.label())
        assertTrue(sortEpubPassages(emptyList(), EpubPassageSort.TEXT_ASC).isEmpty())
    }

    @Test fun `text ascending follows Turkish collation independently of device locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                val sorted = sortEpubPassages(passages, EpubPassageSort.TEXT_ASC)
                assertEquals(listOf("cedilla", "e", "dotless", "dotted", "z"), sorted.map { it.id })
                sorted.forEach { result -> assertSame(passages.first { it.id == result.id }, result) }
            }
        } finally { Locale.setDefault(previous) }
        assertEquals(listOf("z", "dotted", "cedilla", "dotless", "e"), passages.map { it.id })
    }

    @Test fun `equal text keys retain supplied repository order rather than ids or creation time`() {
        val supplied = listOf(
            EpubPassage("third", "ada", anchor, 1),
            EpubPassage("other", "Bahar", anchor, 2),
            EpubPassage("first", "Ada", anchor, 3),
        )
        val sorted = sortEpubPassages(supplied, EpubPassageSort.TEXT_ASC)
        assertEquals(listOf("third", "first", "other"), sorted.map { it.id })
        assertEquals(listOf("third", "other", "first"), supplied.map { it.id })
        assertSame(supplied[0], sorted[0])
        assertSame(supplied[2], sorted[1])
    }
}
