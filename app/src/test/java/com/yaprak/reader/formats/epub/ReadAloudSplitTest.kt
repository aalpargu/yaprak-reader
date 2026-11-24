package com.yaprak.reader.formats.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudSplitTest {
    @Test fun `short paragraph stays one utterance`() {
        assertEquals(listOf("Kısa bir paragraf."), splitForSpeech("Kısa bir paragraf."))
    }

    @Test fun `long paragraph splits at sentence ends within the engine limit and keeps every word`() {
        val sentence = "Güneş doğdu ve yeni bir gün başladı. "
        val text = sentence.repeat(120).trim()
        val parts = splitForSpeech(text, limit = 400)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 400 && it.endsWith(".") })
        assertEquals(text.split(' ').size, parts.sumOf { it.split(' ').size })
    }
}
