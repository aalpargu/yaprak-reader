package com.yaprak.reader.domain

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.DocxAssistantSources as D
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DocxBookAssistantTest {
    private fun failure(sources: PreparedBookSources) =
        (runCatching { BookQuestionRequest.create(sources, "Explain using these sources") }.exceptionOrNull() as BookAnswerException).reason
    @Test fun completeDocxScopeAdmitsMoreThanKeywordAllowanceWithoutInventingPagesOrChapters() {
        val sources = D.sources(); val request = BookQuestionRequest.create(sources, "Explain")
        assertSame(sources, request.sources); assertEquals(12, request.sources.references.size)
        assertTrue(supportsBookSourceRetrieval(BookFormat.DOCX)); assertFalse(sources.truncated)
        assertTrue(sources.section!!.isValid); assertTrue(validBookSourceSections(D.scope, listOf(requireNotNull(sources.section))))
        assertTrue(sources.references.all { it.hit.readingOrderIndex == 0 && it.hit.target.pdfPageIndex == null })
    }
    @Test fun aPartialOrOversizeDocumentCannotBeAuthorizedAsCompleteEvidence() {
        for (count in listOf(12, MAX_COMPLETE_BOOK_SOURCE_REFERENCES + 1)) {
            val complete = D.sources(D.section(count))
            val partial = PreparedBookSources(complete.requestId, complete.scope, "", count, complete.references.take(8), complete.section)
            assertEquals(BookAnswerFailure.SOURCE, failure(partial))
            if (count > MAX_COMPLETE_BOOK_SOURCE_REFERENCES) assertEquals(BookAnswerFailure.SOURCE, failure(complete))
        }
    }
    @Test fun keywordDocxEvidenceRetainsEightSourceLimitAndDoesNotAcquireCompleteScope() {
        val complete = D.sources(); val keyword = PreparedBookSources(UUID.randomUUID().toString(), D.scope,
            "literal", 12, complete.references.take(8))
        assertTrue(keyword.truncated); assertNull(BookQuestionRequest.create(keyword, "Explain").sources.section)
        assertEquals(BookAnswerFailure.SOURCE, failure(PreparedBookSources(UUID.randomUUID().toString(), D.scope,
            "literal", 12, complete.references.take(9))))
    }
    @Test fun onlyExactDocumentTitleOrderHrefAndNoPageCanAuthorizeDocxSource() {
        val section = D.section(1)
        for (bad in listOf(section.copy(chapterTitle = "Chapter 1"), section.copy(readingOrderIndex = 1), section.copy(firstChunkIndex = 1),
            section.copy(resourceHref = "Text/document.xhtml#heading-1"), section.copy(pdfPageIndex = 0),
            section.copy(resourceHref = "Text/part0001.xhtml"))) {
            assertFalse(bad.isValid); assertFalse(validBookSourceSections(D.scope, listOf(bad)))
        }
        val hit = D.hit(section, 0)
        for (bad in listOf(hit.copy(chapterTitle = "Chapter 1"), hit.copy(readingOrderIndex = 1),
            hit.copy(target = hit.target.copy(pdfPageIndex = 0)))) {
            val keyword = PreparedBookSources(UUID.randomUUID().toString(), D.scope, "literal", 1,
                listOf(BookSourceReference("S1", bad)))
            assertEquals(BookAnswerFailure.SOURCE, failure(keyword))
        }
    }
    @Test fun missingLastChunkChangedGenerationOrForeignBookCannotCompleteARequest() {
        val section = D.section(); val hits = (0 until section.chunkCount).map { D.hit(section, it) }
        val changed = listOf(hits.dropLast(1), hits.dropLast(1) + hits.last().copy(generation = UUID.randomUUID().toString()),
            hits.dropLast(1) + hits.last().copy(book = D.book.copy(id = "foreign")),
            hits.dropLast(1) + hits.last().copy(chunkIndex = 0))
        for (bad in changed) assertEquals(BookAnswerFailure.SOURCE, failure(D.sources(section, bad)))
    }
    @Test fun exactQuoteUsesTheRequestSnapshotAndCannotBeRetargetedByAnOldAnswerOrInventedSource() {
        val request = BookQuestionRequest.create(D.sources(), "Explain")
        val source = request.sources.references.last()
        fun answer(id: String = request.sources.requestId, citation: BookAnswerCitation = BookAnswerCitation(source, source.text)) =
            BookAnswer(id, BookAnswerStatus.ANSWERED, listOf(BookAnswerStatement("Explanation", listOf(citation))), "")
        validateBookAnswer(request, answer())
        for (bad in listOf(answer(UUID.randomUUID().toString()), answer(citation = BookAnswerCitation(source, "invented")),
            answer(citation = BookAnswerCitation(BookSourceReference(source.label, source.hit.copy(text = "changed")), "changed")))) {
            val error = runCatching { validateBookAnswer(request, bad) }.exceptionOrNull() as BookAnswerException
            assertEquals(BookAnswerFailure.INVALID_RESPONSE, error.reason)
        }
    }
    @Test fun studyPresetsNameExtractedDocxTextAndNeverClaimAReadPrefixOrWordPage() {
        for (task in BookStudyTask.entries) {
            val question = task.questionForSection(BookFormat.DOCX)
            assertTrue(question.contains("DOCX belgesinin çıkarılmış metni")); assertFalse(question.contains("EPUB"))
            assertFalse(question.contains("5. bölüm")); assertTrue(question.contains("Yalnız gönderilen kaynaklar"))
            BookQuestionRequest.create(D.sources(), question)
        }
        assertTrue(runCatching { BookStudyTask.SUMMARY.questionForSection(BookFormat.CBZ) }.exceptionOrNull() is IllegalArgumentException)
    }
}
