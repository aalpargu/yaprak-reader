package com.yaprak.reader.feature

import com.yaprak.reader.domain.reader.ContentsEntry
import com.yaprak.reader.feature.reader.searchContents
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentsSearchTest {
    private val entries = listOf(
        ContentsEntry("group", "İKİNCİ Kısım", 0, false),
        ContentsEntry("chapter-a", "İstanbul Işıkları", 1, true),
        ContentsEntry("chapter-b", "Sessiz Sabah", 1, true),
        ContentsEntry("chapter-c", "IŞIK altında İSTANBUL", 1, true),
    )

    @Test fun `all query words match a title with Turkish casing independent of device locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                assertEquals(listOf("chapter-a", "chapter-c"),
                    searchContents(entries, "  istanbul\tışık\u00a0 ").map(ContentsEntry::id))
                assertTrue(searchContents(entries, "istanbul sabah").isEmpty())
                assertEquals(listOf("group"), searchContents(entries, "ikinci kısım").map(ContentsEntry::id))
                assertTrue(searchContents(entries, "chapter-a").isEmpty())
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `blank query returns the same list and a match keeps its original entry`() {
        assertSame(entries, searchContents(entries, " \t\n\u00a0"))
        val matching = searchContents(entries, "sabah")
        assertSame(entries[2], matching.single())
        assertEquals(listOf("group", "chapter-a", "chapter-b", "chapter-c"), entries.map(ContentsEntry::id))
    }

    @Test fun `search reaches the last entry in a 5000 item contents list without changing ids or order`() {
        val special = setOf(0, 2500, 4999)
        val large = (0 until 5000).map { index ->
            ContentsEntry("original-$index", if (index in special) "Özel Başlık $index" else "Bölüm $index",
                index % 3, index % 2 == 0)
        }

        val result = searchContents(large, "başlık özel")

        assertEquals(listOf("original-0", "original-2500", "original-4999"), result.map(ContentsEntry::id))
        assertSame(large[0], result[0])
        assertSame(large[2500], result[1])
        assertSame(large[4999], result[2])
        assertEquals(5000, large.size)
    }
}
