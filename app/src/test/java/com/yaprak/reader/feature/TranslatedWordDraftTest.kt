package com.yaprak.reader.feature

import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.translation.TranslationFailure
import com.yaprak.reader.feature.translation.*
import com.yaprak.reader.feature.vocabulary.*
import org.junit.Assert.*
import org.junit.Test

class TranslatedWordDraftTest {
    private val ready = TranslationState("selection", "attempt", " Leaf ", TranslationPhase.READY,
        modelsReady = true, translatedText = " yaprak ")
    @Test fun onlyACompletedModelReadySingleWordCanBecomeAReviewableDraft() {
        val draft = translatedWordDraft(ready)!!
        assertEquals("Leaf", draft.english); assertEquals("yaprak", draft.turkish); assertTrue(draft.isValid)
        for (state in listOf(ready.copy(working = true), ready.copy(modelsReady = false), ready.copy(translatedText = null),
            ready.copy(phase = TranslationPhase.ERROR), ready.copy(failure = TranslationFailure.TRANSLATE),
            ready.copy(sourceText = "two words"), ready.copy(sourceText = "hello."), ready.copy(sourceText = ""),
            ready.copy(sourceText = "\uD800"))) assertNull(translatedWordDraft(state))
    }
    @Test fun meaningRemainsLiteralAndOverlongMultilineOrMalformedOutputIsNeverTruncated() {
        val literal = "<b>İ/ı 🌿</b>"
        assertEquals(literal, translatedWordDraft(ready.copy(translatedText = literal))!!.turkish)
        for (output in listOf("", " ", "a".repeat(201), "line\n", "\nline", "x\r", "x\t",
            "x\u0000", "\uD800", "\uDC00", "x\u2028y", "x\u2029y")) assertNull(translatedWordDraft(ready.copy(translatedText = output)))
        assertEquals(200, translatedWordDraft(ready.copy(translatedText = "a".repeat(200)))!!.turkish.length)
        assertEquals(100, translatedWordDraft(ready.copy(sourceText = "a".repeat(100)))!!.english.length)
        assertNull(translatedWordDraft(ready.copy(sourceText = "a".repeat(101))))
    }
    @Test fun aForgedDraftCannotBypassCanonicalWordAndMeaningValidation() {
        val valid = TranslatedWordDraft("leaf", "yaprak")
        assertTrue(valid.isValid)
        for (draft in listOf(valid.copy(id = "bad"), valid.copy(english = "two words"), valid.copy(english = " leaf"),
            valid.copy(turkish = "meaning\n"), valid.copy(turkish = " x "), valid.copy(turkish = "a".repeat(201)),
            valid.copy(turkish = "\uD800"))) assertFalse(draft.isValid)
    }
    @Test fun bookAndHashOwnTheTemporaryPrefillAndDebugStringsDoNotExposeWords() {
        val book = Book("one", "Title", null, BookFormat.PDF, "one.pdf", "one.pdf", 1, "a".repeat(64), 1)
        val draft = translatedWordDraft(ready)!!
        val prefill = TranslatedWordPrefill(book.id, book.sha256, draft)
        assertTrue(prefill.matches(book)); assertFalse(prefill.matches(book.copy(id = "other")))
        assertFalse(prefill.matches(book.copy(sha256 = "b".repeat(64))))
        assertFalse(prefill.copy(draft = draft.copy(turkish = "")).matches(book))
        assertFalse(draft.toString().contains(draft.english)); assertFalse(draft.toString().contains(draft.turkish))
        assertFalse(prefill.toString().contains(draft.turkish))
    }
}
