package com.yaprak.reader.feature.vocabulary

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.reader.SourceRangeAnchors
import com.yaprak.reader.domain.vocabulary.MAX_TURKISH_MEANING_LENGTH
import com.yaprak.reader.feature.reader.selectedWordCandidate
import com.yaprak.reader.feature.translation.TranslationPhase
import com.yaprak.reader.feature.translation.TranslationState
import java.util.UUID

/** Temporary, reviewable form input; never a translation accuracy or reading-source claim. */
internal data class TranslatedWordDraft(val english: String, val turkish: String, val id: String = UUID.randomUUID().toString()) {
    val isValid get() = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) &&
        english == english.trim() && selectedWordCandidate(english) == english &&
        turkish == turkish.trim() && turkish.length in 1..MAX_TURKISH_MEANING_LENGTH &&
        SourceRangeAnchors.validUtf16(turkish) && turkish.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }
    override fun toString() = "TranslatedWordDraft(id=$id)"
}
internal data class TranslatedWordPrefill(val bookId: String, val contentSha256: String, val draft: TranslatedWordDraft) {
    fun matches(book: Book) = book.id == bookId && book.sha256 == contentSha256 && draft.isValid
    override fun toString() = "TranslatedWordPrefill(bookId=$bookId)"
}
internal fun translatedWordDraft(state: TranslationState): TranslatedWordDraft? {
    if (state.working || !state.modelsReady || state.phase != TranslationPhase.READY || state.failure != null) return null
    val english = selectedWordCandidate(state.sourceText) ?: return null
    val output = state.translatedText ?: return null
    if (output.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return null
    return TranslatedWordDraft(english, output.trim()).takeIf { it.isValid }
}
