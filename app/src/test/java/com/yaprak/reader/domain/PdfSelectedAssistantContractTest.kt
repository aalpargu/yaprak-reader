package com.yaprak.reader.domain

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.fixtures.PdfSelectedAssistantFixtures as P
import com.yaprak.reader.fixtures.SelectedAssistantFixtures as E
import org.junit.Assert.*
import org.junit.Test

class PdfSelectedAssistantContractTest {
    @Test fun pdfNeedsItsOwnNativeIdentityAndCannotBorrowEpubOrMissingGeometry() {
        val request = P.request(); assertTrue(request.isValid)
        for (invalid in listOf(request.copy(sourceSelectionId = null), request.copy(sourceSelectionId = "old"),
            request.copy(pdfSourceAnchor = null), request.copy(anchor = E.request().anchor), request.copy(sourceFormat = BookFormat.EPUB),
            request.copy(sourceFormat = BookFormat.TXT), request.copy(preview = "x".repeat(4097)), request.copy(preview = "bad\u0000")))
            assertFalse(invalid.isValid)
        assertFalse(E.request().copy(pdfSourceAnchor = P.anchor).isValid)
    }
    @Test fun rawSelectedTextIsCompleteAndSplitsWithoutSurrogateCutsOrInventedSeparators() {
        val text = "x".repeat(1999) + "🌿" + "İstanbul\n".repeat(200)
        val request = P.request(text); val sources = P.sources(request)
        assertEquals(text, sources.references.joinToString("") { it.text }); assertEquals(1999, sources.references.first().text.length)
        assertTrue(sources.references.all { it.text.length <= 2000 && validAnswerText(it.text, 2000) })
        assertSame(sources, SelectedTextQuestionRequest.create(sources, "Açıkla").sources)
        val partial = PreparedSelectedTextSources(request, P.book, sources.references.take(1))
        assertEquals(BookAnswerFailure.SOURCE, (runCatching { SelectedTextQuestionRequest.create(partial, "Soru") }.exceptionOrNull() as BookAnswerException).reason)
    }
    @Test fun preparedBookMustMatchFormatActualPageCountAndWholeSelection() {
        val request = P.request(); val refs = P.sources(request).references
        for (book in listOf(P.book.copy(format = BookFormat.EPUB), P.book.copy(pageCount = null), P.book.copy(pageCount = 2),
            P.book.copy(id = "other"), P.book.copy(sha256 = "b".repeat(64))))
            assertEquals(BookAnswerFailure.SOURCE, (runCatching { SelectedTextQuestionRequest.create(
                PreparedSelectedTextSources(request, book, refs), "Soru") }.exceptionOrNull() as BookAnswerException).reason)
    }
    @Test fun quotesAreBoundToThisPdfRequestAndDebugStringsNeverExposeTextOrGeometry() {
        val sources = P.sources(); val request = SelectedTextQuestionRequest.create(sources, "Açıkla")
        val answer = E.answer(request); validateSelectedTextAnswer(request, answer)
        val other = SelectedTextQuestionRequest.create(P.sources(), "Açıkla")
        assertTrue(runCatching { validateSelectedTextAnswer(other, answer) }.isFailure)
        assertFalse(sources.request.toString().contains(sources.request.preview)); assertFalse(sources.toString().contains("pageWidth"))
    }
}
