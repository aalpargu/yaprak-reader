package com.yaprak.reader.domain

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.fixtures.CompleteFormatSources as C
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CompleteFormatSourceTest {
    private val formats = listOf(BookFormat.PDF, BookFormat.TXT, BookFormat.FB2)
    private fun refused(sources: PreparedBookSources) {
        assertEquals(BookAnswerFailure.SOURCE, (runCatching { BookQuestionRequest.create(sources, "Özetle") }
            .exceptionOrNull() as BookAnswerException).reason)
    }
    @Test fun completeTypedPageAndPartKeepEveryReferenceAndRequestBoundExactCitation() {
        for (format in formats) {
            val section = C.section(format); val sources = C.sources(section)
            assertTrue(section.isValid); assertTrue(validCompleteSectionSources(sources)); assertFalse(sources.truncated)
            val request = BookQuestionRequest.create(sources, BookStudyTask.SUMMARY.questionForSection(format))
            assertSame(sources, request.sources)
            val last = sources.references.last()
            val answer = BookAnswer(sources.requestId, BookAnswerStatus.ANSWERED,
                listOf(BookAnswerStatement("Son kaynak", listOf(BookAnswerCitation(last, last.text)))), "")
            validateBookAnswer(request, answer)
            assertEquals(11, answer.statements.single().citations.single().source.hit.chunkIndex)
        }
    }
    @Test fun realNoncontiguousPdfPagesShareEmptyHrefButCannotDuplicateOrMixCatalogKinds() {
        val first = C.section(BookFormat.PDF, 2).copy(readingOrderIndex = 0, pdfPageIndex = 0)
        val second = first.copy(readingOrderIndex = 2, pdfPageIndex = 2, firstChunkIndex = 2)
        assertTrue(validBookSourceSections(F.scope, listOf(first, second)))
        for (wrong in listOf(second.copy(pdfPageIndex = 0), second.copy(firstChunkIndex = 3),
            second.copy(chapterTitle = "Invented chapter"), second.copy(resourceHref = "EPUB/a.xhtml"),
            second.copy(generation = UUID.randomUUID().toString()), C.section(BookFormat.TXT, 2).copy(firstChunkIndex = 2)))
            assertFalse(validBookSourceSections(F.scope, listOf(first, wrong)))
        assertFalse(first.copy(sourceFormat = BookFormat.CBZ).isValid)
    }
    @Test fun canonicalTxtPartCannotBorrowAnEpubTitleDifferentPartOrPdfIndex() {
        val section = C.section(BookFormat.TXT)
        for (wrong in listOf(section.copy(chapterTitle = null), section.copy(chapterTitle = "Chapter three"),
            section.copy(resourceHref = "Text/part0002.xhtml"), section.copy(readingOrderIndex = 64),
            section.copy(pdfPageIndex = 2), section.copy(resourceHref = "../Text/part0003.xhtml"))) assertFalse(wrong.isValid)
        val first = section.copy(readingOrderIndex = 0, chapterTitle = "Metin 1", resourceHref = "Text/part0001.xhtml", chunkCount = 1)
        assertTrue(validBookSourceSections(F.scope, listOf(first, section.copy(firstChunkIndex = 1))))
    }
    @Test fun partialKeywordReorderedCrossFormatAndChangedBookSourcesCannotClaimCompleteEvidence() {
        for (format in formats) {
            val section = C.section(format); val full = C.sources(section)
            fun altered(refs: List<BookSourceReference> = full.references, query: String = "", chosen: BookSourceSection = section) =
                PreparedBookSources(full.requestId, full.scope, query, full.totalMatches, refs, chosen)
            refused(altered(full.references.take(8))); refused(altered(full.references.reversed())); refused(altered(query = "Güneş"))
            refused(altered(chosen = section.copy(firstChunkIndex = 1)))
            refused(altered(chosen = section.copy(sourceFormat = BookFormat.EPUB)))
            for (changed in listOf(full.references.first().hit.copy(book = C.book(format).copy(title = "Changed")),
                full.references.first().hit.copy(target = F.hit.target),
                full.references.first().hit.copy(book = C.book(if (format == BookFormat.PDF) BookFormat.TXT else BookFormat.PDF))))
                refused(altered(full.references.toMutableList().also { it[0] = it[0].copy(hit = changed) }))
            if (format == BookFormat.PDF) refused(C.sources(section, C.book(format).copy(pageCount = 2)))
        }
    }
    @Test fun complete64IsAcceptedBut65OrPartialFirst64IsAWholeFailure() {
        for (format in formats) {
            val maximum = C.section(format, 64)
            assertEquals(64, BookQuestionRequest.create(C.sources(maximum), "Özetle").sources.references.size)
            val over = maximum.copy(chunkCount = 65); assertTrue(over.isValid); assertFalse(over.fitsSingleRequest)
            refused(C.sources(over))
            val full = C.sources(over)
            refused(PreparedBookSources(full.requestId, full.scope, "", 65, full.references.take(64), over))
        }
    }
    @Test fun summaryAndPracticeOnlyDraftTheActualTypedScopeAndNeverClaimChaptersOrReadPrefix() {
        for (format in formats) for (task in BookStudyTask.entries) {
            val question = task.questionForSection(format)
            assertTrue(validAnswerText(question, MAX_BOOK_QUESTION)); assertFalse(question.contains("EPUB"))
            assertTrue(question.contains(when (format) {
                BookFormat.PDF -> "PDF sayfasının çıkarılmış metni"; BookFormat.FB2 -> "FB2 metin parçası"; else -> "TXT metin parçası"
            }))
            assertTrue(question.contains("kaynak")); assertTrue(question.contains("desteklenmeyen"))
        }
        assertEquals(BookStudyTask.SUMMARY.question, BookStudyTask.SUMMARY.questionForSection(BookFormat.EPUB))
        assertTrue(runCatching { BookStudyTask.SUMMARY.questionForSection(BookFormat.CBZ) }.isFailure)
    }
}
