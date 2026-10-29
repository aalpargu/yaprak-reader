package com.yaprak.reader.feature

import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.feature.passages.limitEpubPassageSearchQuery
import com.yaprak.reader.feature.passages.searchEpubPassages
import com.yaprak.reader.reader.SourceTextAnchor
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubPassageSearchTest {
    private val anchor = SourceTextAnchor("chapter.xhtml", 1, 5, "a".repeat(64))
    private val passages = listOf(
        EpubPassage("first", "IŞIK ve gece\nİstanbul", anchor, 3),
        EpubPassage("second", "Sessiz bir sabah", anchor, 2),
        EpubPassage("third", "Işık altında sabah", anchor, 1),
    )

    @Test fun `empty and Unicode whitespace queries return the original list`() {
        assertSame(passages, searchEpubPassages(passages, ""))
        assertSame(passages, searchEpubPassages(passages, " \t\n\r\u00a0\u2003\u202f"))
        assertTrue(searchEpubPassages(emptyList(), "ışık").isEmpty())
    }

    @Test fun `all whitespace separated terms match multiline text in supplied order`() {
        assertEquals(listOf("third"), searchEpubPassages(passages, "  sabah\tışık\u00a0").map { it.id })
        assertEquals(listOf("first"), searchEpubPassages(passages, "gece\u2003İstanbul").map { it.id })
        assertTrue(searchEpubPassages(passages, "ışık sabah gece").isEmpty())
        val reordered = listOf(passages[2], passages[1], passages[0])
        val result = searchEpubPassages(reordered, "ışık")
        assertEquals(listOf("third", "first"), result.map { it.id })
        assertSame(passages[2], result[0])
        assertSame(passages[0], result[1])
        assertEquals(listOf("first", "second", "third"), passages.map { it.id })
    }

    @Test fun `Turkish I forms match independently of device locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("lt-LT"))) {
                Locale.setDefault(locale)
                assertEquals(listOf("first", "third"), searchEpubPassages(passages, "ışık").map { it.id })
                assertEquals(listOf("first"), searchEpubPassages(passages, "IŞIK istanbul").map { it.id })
                assertEquals(listOf("first"), searchEpubPassages(passages, "ışık İSTANBUL").map { it.id })
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `full saved text is searched and anchor metadata or omitted entries are outside scope`() {
        val longPassage = EpubPassage("long", "prefix ".repeat(100) + "\nSaklı cümle", anchor, 5)
        val metadataOnly = EpubPassage(
            "metadata-secret", "Diğer kayıt",
            SourceTextAnchor("metadata-secret.xhtml", 202, 303, "f".repeat(64)), 9,
        )
        val supplied = listOf(longPassage, metadataOnly)
        assertEquals(listOf("long"), searchEpubPassages(supplied, "saklı cümle").map { it.id })
        assertTrue(searchEpubPassages(supplied, "metadata-secret").isEmpty())
        assertTrue(searchEpubPassages(supplied, "f".repeat(64)).isEmpty())
        assertTrue(searchEpubPassages(supplied, "202").isEmpty())
        assertTrue(searchEpubPassages(supplied, "İstanbul").isEmpty())
        assertEquals(listOf(longPassage, metadataOnly), supplied)
    }

    @Test fun `query cap preserves exact limits and never splits a UTF16 pair`() {
        assertEquals("x".repeat(199), limitEpubPassageSearchQuery("x".repeat(199)))
        assertEquals("x".repeat(200), limitEpubPassageSearchQuery("x".repeat(200)))
        assertEquals("x".repeat(200), limitEpubPassageSearchQuery("x".repeat(201)))
        assertEquals("x".repeat(198) + "😀", limitEpubPassageSearchQuery("x".repeat(198) + "😀y"))
        assertEquals("x".repeat(199), limitEpubPassageSearchQuery("x".repeat(199) + "😀y"))
    }
}
