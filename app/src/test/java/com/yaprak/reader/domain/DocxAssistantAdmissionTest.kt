package com.yaprak.reader.domain

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.reader.SourceTextAnchor
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DocxAssistantAdmissionTest {
    private val book = Book("docx", "Book", null, BookFormat.DOCX, "source.docx", "source.docx", 1, "a".repeat(64), 1)
    private val scope = BookTextScope(book.id, book.sha256)
    private val target = LibraryTextTarget(book.id, book.sha256, "Text/document.xhtml", "b".repeat(64), sourceFormat = BookFormat.DOCX)

    @Test fun searchAddressDoesNotAuthorizeFabricatedWordChapterEvidence() {
        assertTrue(supportsBookSourceRetrieval(BookFormat.DOCX)); assertTrue(target.isValid)
        val section = BookSourceSection(scope, UUID.randomUUID().toString(), 0, target.resourceHref, target.resourceSha256,
            "Original heading", 0, 1, sourceFormat = BookFormat.DOCX)
        assertFalse(section.isValid); assertFalse(section.fitsSingleRequest)
        assertFalse(validBookSourceSections(scope, listOf(section)))
        for (task in BookStudyTask.entries) assertTrue(task.questionForSection(BookFormat.DOCX).contains("DOCX belgesinin çıkarılmış metni"))
    }

    @Test fun evenWellFormedEpubSelectionCoordinatesCannotAuthorizeSelectedDocxAi() {
        val request = SelectedTextSourceRequest(book.id, book.sha256, UUID.randomUUID().toString(), "lit",
            SourceTextAnchor(target.resourceHref, 0, 3, "b".repeat(64)), BookFormat.EPUB)
        assertTrue(request.isValid)
        val docx = request.copy(sourceFormat = BookFormat.DOCX)
        assertFalse(docx.isValid)
        val prepared = PreparedSelectedTextSources(docx, book, listOf(SelectedTextReference("S1", "lit")))
        val failure = runCatching { SelectedTextQuestionRequest.create(prepared, "Açıkla") }.exceptionOrNull()
        assertEquals(BookAnswerFailure.SOURCE, (failure as BookAnswerException).reason)
    }

    @Test fun fabricatedDocxIndexHitIsRejectedBeforeAnyBookQuestionRequestExists() {
        val hit = LibraryTextHit(1, UUID.randomUUID().toString(), book, target, "Heading", "Original text", "Original", 0, 0)
        val prepared = PreparedBookSources(UUID.randomUUID().toString(), scope, "original", 1, listOf(BookSourceReference("S1", hit)))
        val failure = runCatching { BookQuestionRequest.create(prepared, "Açıkla") }.exceptionOrNull()
        assertEquals(BookAnswerFailure.SOURCE, (failure as BookAnswerException).reason)
    }
}
