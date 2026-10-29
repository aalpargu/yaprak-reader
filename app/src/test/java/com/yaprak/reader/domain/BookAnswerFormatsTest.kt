package com.yaprak.reader.domain

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import org.junit.Assert.*
import org.junit.Test

class BookAnswerFormatsTest {
    private fun hit(format: BookFormat): LibraryTextHit {
        val book = F.book.copy(format = format, originalName = "source.${format.name.lowercase()}",
            storageName = "source.${format.name.lowercase()}", pageCount = if (format == BookFormat.PDF) 3 else null)
        return F.hit.copy(book = book, readingOrderIndex = 1, chapterTitle = if (format == BookFormat.TXT || format == BookFormat.FB2) "Metin 2" else null,
            target = F.hit.target.copy(sourceFormat = format,
                resourceHref = when (format) { BookFormat.PDF -> ""; BookFormat.FB2 -> "Text/b1-p2.xhtml"; else -> "Text/part0002.xhtml" },
                pdfPageIndex = if (format == BookFormat.PDF) 1 else null))
    }
    @Test fun typedPdfTxtAndFb2SourcesKeepTheirOwnRequestBoundCitations() {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT, BookFormat.FB2)) {
            val hit = hit(format); val request = BookQuestionRequest.create(F.sources(hit), "Bu kavramı açıkla")
            val answer = F.answer(request)
            validateBookAnswer(request, answer)
            assertEquals(hit, answer.statements.single().citations.single().source.hit)
            assertEquals(format, request.sources.references.single().hit.target.sourceFormat)
            assertEquals(BookAnswerFailure.INVALID_RESPONSE, failure { validateBookAnswer(BookQuestionRequest.create(F.sources(hit), "Başka soru"), answer) })
        }
    }
    @Test fun pdfPageCannotBorrowChapterAddressPrintedPageOrAnotherOriginalPage() {
        val hit = hit(BookFormat.PDF)
        for (invalid in listOf(
            hit.copy(chapterTitle = "invented chapter"),
            hit.copy(readingOrderIndex = 0),
            hit.copy(book = hit.book.copy(pageCount = 1)),
            hit.copy(target = hit.target.copy(pdfPageIndex = 3)),
            hit.copy(target = F.hit.target),
            hit.copy(target = hit.target.copy(resourceHref = "chapter.xhtml")),
        )) assertEquals(BookAnswerFailure.SOURCE, failure { BookQuestionRequest.create(F.sources(invalid), "Soru") })
    }
    @Test fun txtSourceRetainsCanonicalPartIdentityAndCannotBecomeAnEpubNodeOrPdfPage() {
        val hit = hit(BookFormat.TXT)
        for (invalid in listOf(
            hit.copy(readingOrderIndex = 0), hit.copy(chapterTitle = null), hit.copy(chapterTitle = "Başlık"),
            hit.copy(target = hit.target.copy(resourceHref = "Text/part0001.xhtml")),
            hit.copy(target = hit.target.copy(pdfPageIndex = 1)), hit.copy(target = F.hit.target),
        )) assertEquals(BookAnswerFailure.SOURCE, failure { BookQuestionRequest.create(F.sources(invalid), "Soru") })
    }
    @Test fun aSingleRequestCannotMixFormatsOrMetadataUnderTheSameBookHash() {
        val first = hit(BookFormat.PDF)
        for (foreign in listOf(hit(BookFormat.TXT), hit(BookFormat.FB2), first.copy(book = first.book.copy(title = "replaced title")), F.hit)) {
            val sources = PreparedBookSources(F.sources().requestId, F.scope, "Güneş", 2,
                listOf(BookSourceReference("S1", first), BookSourceReference("S2", foreign.copy(id = 2))))
            assertEquals(BookAnswerFailure.SOURCE, failure { BookQuestionRequest.create(sources, "Soru") })
        }
        val comic = hit(BookFormat.PDF).let { it.copy(book = it.book.copy(format = BookFormat.CBZ)) }
        assertEquals(BookAnswerFailure.SOURCE, failure { BookQuestionRequest.create(F.sources(comic), "Soru") })
    }
    @Test fun outOfRangeReadingOrderAndChunkCannotEnterTheBoundedEvidenceSnapshot() {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT, BookFormat.FB2)) {
            val hit = hit(format)
            for (invalid in listOf(hit.copy(chunkIndex = MAX_INDEX_BOOK_CHUNKS), hit.copy(readingOrderIndex = MAX_INDEX_BOOK_RESOURCES)))
                assertEquals(BookAnswerFailure.SOURCE, failure { BookQuestionRequest.create(F.sources(invalid), "Soru") })
        }
    }
    private fun failure(block: () -> Unit) = (runCatching(block).exceptionOrNull() as BookAnswerException).reason
}
