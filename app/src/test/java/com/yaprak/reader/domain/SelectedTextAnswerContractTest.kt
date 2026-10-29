package com.yaprak.reader.domain

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.fixtures.SelectedAssistantFixtures as F
import com.yaprak.reader.reader.*
import org.junit.Assert.*
import org.junit.Test

class SelectedTextAnswerContractTest {
    @Test fun nativeParagraphSeparatorsNeverBecomeSource() {
        val anchor = SourceRangeAnchor("EPUB/chapter.xhtml", listOf(
            SourceRangeSegment("0/0", 0, 6, F.hash("İlk 🌿"), "İlk 🌿"),
            SourceRangeSegment("1/0", 0, 3, F.hash("Son"), "Son")))
        val request = F.request().copy(preview = "İlk 🌿\n\nSon", anchor = anchor)
        val good = PreparedSelectedTextSources(request, F.book, listOf(SelectedTextReference("S1", "İlk 🌿Son")))
        assertEquals("İlk 🌿Son", SelectedTextQuestionRequest.create(good, " Açıkla ").sources.references.single().text)
        bad(BookAnswerFailure.SOURCE) { SelectedTextQuestionRequest.create(
            PreparedSelectedTextSources(request, F.book, listOf(SelectedTextReference("S1", request.preview))), "Açıkla") }
    }
    @Test fun wrongBookHashFormatAndPartialSelectionFail() {
        val good = F.sources()
        for (book in listOf(F.book.copy(id = "other"), F.book.copy(sha256 = "b".repeat(64)), F.book.copy(format = BookFormat.PDF)))
            bad(BookAnswerFailure.SOURCE) { SelectedTextQuestionRequest.create(PreparedSelectedTextSources(good.request, book, good.references), "Soru") }
        bad(BookAnswerFailure.SOURCE) { SelectedTextQuestionRequest.create(PreparedSelectedTextSources(good.request, F.book,
            listOf(SelectedTextReference("S1", "Güneş doğdu."))), "Soru") }
    }
    @Test fun invalidAnchorsIdentitiesAndUnicodeFail() {
        val initial = F.request()
        for (request in listOf(initial.copy(selectionId = "foreign"), initial.copy(contentSha256 = "hash"),
            initial.copy(preview = "\uD800"), initial.copy(anchor = (initial.anchor as SourceTextAnchor).copy(resourceHref = "../other.xhtml")),
            initial.copy(anchor = (initial.anchor as SourceTextAnchor).copy(nodeTextSha256 = "hash")))) {
            assertFalse(request.isValid)
            bad(BookAnswerFailure.SOURCE) { SelectedTextQuestionRequest.create(F.sources(request), "Soru") }
        }
    }
    @Test fun sourceLabelsLimitsQuestionsAndImmutableSnapshots() {
        val good = F.sources()
        for (references in listOf(emptyList(), listOf(SelectedTextReference("S2", F.text)),
            listOf(SelectedTextReference("S1", " ")), listOf(SelectedTextReference("S1", "x".repeat(2001)))))
            bad(BookAnswerFailure.SOURCE) { SelectedTextQuestionRequest.create(PreparedSelectedTextSources(good.request, F.book, references), "Soru") }
        for (question in listOf("", " ", "\u0000", "x".repeat(1001), "\uD800"))
            bad(BookAnswerFailure.INVALID_INPUT) { SelectedTextQuestionRequest.create(good, question) }
        val mutable = good.references.toMutableList()
        val detached = PreparedSelectedTextSources(good.request, F.book, mutable)
        mutable.clear(); assertEquals(1, detached.references.size)
        assertTrue(runCatching { (detached.references as MutableList<*>).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertFalse(detached.toString().contains(F.text))
    }
    @Test fun citationsAreBoundToThisRequestAndExactRawQuote() {
        val request = F.question()
        validateSelectedTextAnswer(request, F.answer(request))
        val source = request.sources.references.single()
        for ((ref, quote) in listOf(source.copy(label = "S2") to "Güneş", source.copy(text = "Güneş") to "Güneş",
            source to "Uydurulmuş alıntı", source to "\uD800"))
            bad(BookAnswerFailure.INVALID_RESPONSE) { validateSelectedTextAnswer(request, SelectedTextAnswer(request.sources.requestId,
                BookAnswerStatus.ANSWERED, listOf(SelectedTextStatement("Cevap", listOf(SelectedTextCitation(ref, quote)))), "")) }
        bad(BookAnswerFailure.INVALID_RESPONSE) { validateSelectedTextAnswer(request,
            SelectedTextAnswer(java.util.UUID.randomUUID().toString(), BookAnswerStatus.INSUFFICIENT, emptyList(), "Yetersiz")) }
    }
    @Test fun insufficientAndOversizedAnswersCannotPretendSuccess() {
        val request = F.question()
        validateSelectedTextAnswer(request, SelectedTextAnswer(request.sources.requestId, BookAnswerStatus.INSUFFICIENT, emptyList(), "Kaynak yetersiz."))
        for (answer in listOf(SelectedTextAnswer(request.sources.requestId, BookAnswerStatus.INSUFFICIENT, emptyList(), ""),
            SelectedTextAnswer(request.sources.requestId, BookAnswerStatus.ANSWERED, emptyList(), ""),
            SelectedTextAnswer(request.sources.requestId, BookAnswerStatus.ANSWERED,
                listOf(SelectedTextStatement("x".repeat(1001), listOf(SelectedTextCitation(request.sources.references.single(), "Güneş")))), "")))
            bad(BookAnswerFailure.INVALID_RESPONSE) { validateSelectedTextAnswer(request, answer) }
    }
    private fun bad(reason: BookAnswerFailure, block: () -> Unit) {
        assertEquals(reason, (runCatching(block).exceptionOrNull() as? BookAnswerException)?.reason)
    }
}
