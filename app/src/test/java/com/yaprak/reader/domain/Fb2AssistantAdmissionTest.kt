package com.yaprak.reader.domain

import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.domain.assistant.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class Fb2AssistantAdmissionTest {
    private val book = Book("fb2", "Book", null, BookFormat.FB2, "source.fb2", "source.fb2", 1, "a".repeat(64), 1)
    private val target = LibraryTextTarget(book.id, book.sha256, "Text/b1-p1.xhtml", "b".repeat(64), sourceFormat = BookFormat.FB2)
    private val scope = BookTextScope(book.id, book.sha256)
    private val generation = UUID.randomUUID().toString()
    private val hit = LibraryTextHit(1, generation, book, target, "Original title", "Özgün kaynak metni.", "Özgün kaynak", 0, 0)
    private fun sources() = PreparedBookSources(UUID.randomUUID().toString(), scope, "kaynak", 1, listOf(BookSourceReference("S1", hit)))
    @Test fun originalFb2WholeBookRetrievalIsAdmitted() { assertTrue(supportsBookSourceRetrieval(BookFormat.FB2)) }
    @Test fun originalFb2KeywordRequestRetainsItsTypedDetachedSourceAndExactQuestion() {
        val sources = sources()
        val request = BookQuestionRequest.create(sources, "Kaynağı açıkla")
        assertSame(sources, request.sources); assertEquals("Kaynağı açıkla", request.question)
        assertEquals(BookFormat.FB2, request.sources.references.single().hit.target.sourceFormat)
    }
    @Test fun completeOriginalFb2PartIsValidWithoutBecomingAnEpubTocChapter() {
        val section = BookSourceSection(scope, generation, 0, target.resourceHref, target.resourceSha256, hit.chapterTitle, 0, 1, sourceFormat = BookFormat.FB2)
        assertTrue(section.isValid); assertTrue(section.fitsSingleRequest)
        assertTrue(validBookSourceSections(scope, listOf(section)))
        assertFalse(section.copy(pdfPageIndex = 0).isValid)
        assertFalse(section.copy(resourceHref = "../Text/b1-p1.xhtml").isValid)
    }
    @Test fun completePartQuestionDraftNamesFb2AndNeverClaimsReadPrefixOrEpubScope() {
        for (task in BookStudyTask.entries) {
            val text = task.questionForSection(BookFormat.FB2)
            assertTrue(text.contains("FB2 metin parçası")); assertFalse(text.contains("EPUB"))
            assertTrue(text.contains("gönderilen kaynaklar"))
        }
    }
    @Test fun comicsAndMalformedOriginalTargetsRemainOutsideTheBookQuestionBoundary() {
        assertFalse(supportsBookSourceRetrieval(BookFormat.CBZ))
        val invalid = PreparedBookSources(UUID.randomUUID().toString(), scope, "kaynak", 1,
            listOf(BookSourceReference("S1", hit.copy(target = target.copy(pdfPageIndex = 0)))))
        assertEquals(BookAnswerFailure.SOURCE, (runCatching { BookQuestionRequest.create(invalid, "Açıkla") }.exceptionOrNull() as BookAnswerException).reason)
    }
}
