package com.yaprak.reader.feature

import com.yaprak.reader.feature.reader.selectedWordCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedWordCandidateTest {
    @Test fun `one Unicode word or joined word is accepted`() {
        listOf("leaf", "çağrı", "κόσμος", "𐐀", "don't", "don’t", "well-being", "Ankara'ya")
            .forEach { assertEquals(it, selectedWordCandidate(it)) }
        assertEquals("leaf", selectedWordCandidate("  leaf \n"))
        assertEquals("a".repeat(100), selectedWordCandidate("a".repeat(100)))
        assertEquals("𐐀".repeat(50), selectedWordCandidate("𐐀".repeat(50)))
    }

    @Test fun `phrases digits punctuation and overlong selections are rejected`() {
        listOf("", "  ", "two words", "two\nwords", "word2", "2word", "123", "word.",
            "(word)", "word!", "-word", "word-", "'word", "word’", "a--b", "a''b", "a’-b",
            "a_b", "word🙂", "a".repeat(101), "𐐀".repeat(51))
            .forEach { assertNull(it, selectedWordCandidate(it)) }
    }
}
