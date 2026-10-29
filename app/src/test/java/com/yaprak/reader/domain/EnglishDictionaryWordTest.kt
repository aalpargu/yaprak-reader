package com.yaprak.reader.domain

import com.yaprak.reader.domain.dictionary.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class EnglishDictionaryWordTest {
    @Test fun `single Unicode lexeme normalizes with ROOT without paragraph context`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("information", englishDictionaryWord("  INFORMATION  "))
            assertEquals("mother-in-law", englishDictionaryWord("Mother-in-law"))
            assertEquals("isn’t", englishDictionaryWord("isn’t"))
            assertEquals("naïve", englishDictionaryWord("naïve"))
            assertEquals("e\u0301lan", englishDictionaryWord("e\u0301lan"))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun `sentences URLs controls surrogates and misplaced connectors are rejected before lookup`() {
        listOf("", " ", "two words", "line\nbreak", "x/y", "https://example.com", "leaf?x=y", "😀",
            "bad\u0000word", "\uD800", "\uDC00", "-word", "word-", "two--words", "'leaf", "leaf'", "\u0301bad", "a-\u0301b").forEach {
            assertEquals("input length ${it.length}", DictionaryFailure.INVALID_WORD,
                (runCatching { englishDictionaryWord(it) }.exceptionOrNull() as DictionaryException).reason)
        }
    }
    @Test fun `exact word bound permits 100 and rejects 101 UTF16 units`() {
        assertEquals("a".repeat(100), englishDictionaryWord("a".repeat(100)))
        assertEquals("i\u0307".repeat(50), englishDictionaryWord("İ".repeat(50)))
        assertEquals(DictionaryFailure.INVALID_WORD,
            (runCatching { englishDictionaryWord("İ".repeat(51)) }.exceptionOrNull() as DictionaryException).reason)
        assertEquals(DictionaryFailure.INVALID_WORD,
            (runCatching { englishDictionaryWord("a".repeat(101)) }.exceptionOrNull() as DictionaryException).reason)
    }
}
