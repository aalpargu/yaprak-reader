package com.yaprak.reader.feature

import com.yaprak.reader.feature.reader.selectedNoteCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedNoteCandidateTest {
    @Test fun `trimmed multiline and punctuation text becomes a note draft`() {
        assertEquals("Birinci satır.\nİkinci satır!", selectedNoteCandidate(" \nBirinci satır.\nİkinci satır! \t"))
        assertEquals("?", selectedNoteCandidate(" ? "))
        assertEquals("a".repeat(2000), selectedNoteCandidate("a".repeat(2000)))
    }

    @Test fun `blank and overlong selections are rejected without truncation`() {
        listOf("", " \t\n ", "a".repeat(2001), " a".repeat(1001))
            .forEach { assertNull(selectedNoteCandidate(it)) }
    }

    @Test fun `emoji boundary counts UTF-16 units`() {
        assertEquals("🙂".repeat(1000), selectedNoteCandidate("🙂".repeat(1000)))
        assertNull(selectedNoteCandidate("🙂".repeat(1001)))
        assertNull(selectedNoteCandidate("a".repeat(1999) + "🙂"))
    }
}
