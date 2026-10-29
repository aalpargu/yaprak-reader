package com.yaprak.reader.domain

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class BookSourceChapterTest {
    private val generation = UUID.randomUUID().toString()
    private val raw = "BEFORE 🌿ADMITTED TEXT🌿 AFTER"
    private val from = raw.indexOf("ADMITTED")
    private val end = raw.indexOf("🌿 AFTER")
    private val hit = F.hit.copy(generation = generation, text = raw)
    private val start = BookChapterBoundary(0, hit.target.resourceHref, hit.target.resourceSha256, from)
    private val chapter = BookSourceChapter(F.scope, generation, "c".repeat(64), 0, "Chosen title", 0, start,
        start.copy(textOffset = end), 1)
    private val source = BookSourceReference("S1", hit, BookSourceExcerpt(0, from, end))
    private fun prepared(selected: BookSourceChapter = chapter, refs: List<BookSourceReference> = listOf(source)) =
        PreparedBookSources(UUID.randomUUID().toString(), F.scope, "", refs.size, refs, chapter = selected)
    private fun refuse(block: () -> Unit) {
        try { block(); fail("Expected refusal") } catch (_: BookAnswerException) {}
    }

    @Test fun onlyExactExcerptTextIsAdmittedAndQuotedWhileTheOriginalHitRemainsUnchanged() {
        val request = BookQuestionRequest.create(prepared(), "Özetle")
        assertEquals("ADMITTED TEXT", source.text); assertEquals(raw, source.hit.text)
        val answer = BookAnswer(request.sources.requestId, BookAnswerStatus.ANSWERED,
            listOf(BookAnswerStatement("Supported.", listOf(BookAnswerCitation(source, "ADMITTED TEXT")))), "")
        validateBookAnswer(request, answer)
        refuse { validateBookAnswer(request, BookAnswer(request.sources.requestId, BookAnswerStatus.ANSWERED,
            listOf(BookAnswerStatement("Unsupported.", listOf(BookAnswerCitation(source, "BEFORE")))), "")) }
    }

    @Test fun arbitraryExcerptRequiresChapterEvidenceAndSectionAndChapterScopesCannotCoexist() {
        refuse { BookQuestionRequest.create(PreparedBookSources(UUID.randomUUID().toString(), F.scope, "keyword", 1,
            listOf(source)), "Question") }
        val section = BookSourceSection(F.scope, generation, 0, start.resourceHref, start.resourceSha256, hit.chapterTitle, 0, 1)
        refuse { BookQuestionRequest.create(PreparedBookSources(UUID.randomUUID().toString(), F.scope, "", 1,
            listOf(source), section, chapter), "Question") }
        refuse { BookQuestionRequest.create(PreparedBookSources(UUID.randomUUID().toString(), F.scope, "", 1,
            listOf(source), section), "Question") }
    }

    @Test fun malformedSurrogateSliceOffsetsAndOutOfChapterRangesRefuseWithoutCrashing() {
        for (slice in listOf(BookSourceExcerpt(0, Int.MAX_VALUE, Int.MAX_VALUE), BookSourceExcerpt(-1, from, end),
            BookSourceExcerpt(0, from - 1, end), BookSourceExcerpt(0, from, end + 1),
            BookSourceExcerpt(0, 0, raw.length), BookSourceExcerpt(MAX_INDEX_RESOURCE_BYTES, from, end))) {
            refuse { BookQuestionRequest.create(prepared(refs = listOf(source.copy(excerpt = slice))), "Question") }
        }
        assertFalse(BookSourceExcerpt(0, from - 1, end).isValid(raw))
        assertFalse(BookSourceExcerpt(0, from, end + 1).isValid(raw))
        assertFalse(BookSourceExcerpt(0, Int.MAX_VALUE, Int.MIN_VALUE).isValid(raw))
    }

    @Test fun generationTargetCountQueryAndSourceOrderCannotRetargetAChapterSnapshot() {
        for (wrong in listOf(chapter.copy(generation = UUID.randomUUID().toString()), chapter.copy(sourceCount = 2),
            chapter.copy(start = start.copy(resourceSha256 = "d".repeat(64))),
            chapter.copy(start = start.copy(textOffset = from + 1)), chapter.copy(end = start.copy(textOffset = end - 1)))) {
            refuse { BookQuestionRequest.create(prepared(wrong), "Question") }
        }
        assertTrue(validBookSourceChapters(F.scope, listOf(chapter)))
        assertFalse(validBookSourceChapters(F.scope, listOf(chapter, chapter.copy(tocIndex = 1, generation = UUID.randomUUID().toString()))))
        assertFalse(validBookSourceChapters(F.scope, listOf(chapter.copy(title = "\uD800"))))
    }

    @Test fun originalOverlapIsRemovedExactlyWithoutLosingChapterBoundaryText() {
        val text = "a".repeat(1744) + "🌿END INSIDE" + "b".repeat(1000)
        val chunks = listOf(ExtractedTextChunk(0, 0, start.resourceHref, start.resourceSha256, null, text.substring(0, 2000)),
            ExtractedTextChunk(0, 1, start.resourceHref, start.resourceSha256, null, text.substring(1744)))
        val document = ExtractedBookText(F.book.id, F.book.sha256, chunks, 1)
        val proof = ExtractedBookChapters(document, emptyList(), listOf(0, 1744))
        val pieces = extractedChapterPieces(proof, start.copy(textOffset = 1730), start.copy(textOffset = 2200))
        val joined = pieces.joinToString("") { chunks[it.chunkIndex].text.substring(it.excerpt.startUtf16, it.excerpt.endUtf16) }
        assertEquals(text.substring(1730, 2200), joined)
        assertEquals(listOf(0, 1), pieces.map { it.chunkIndex })
        assertEquals(256, pieces.last().excerpt.startUtf16)
    }

    @Test fun completeChapterCapacityAndScopeValidationStayIndependentOfKeywordLimit() {
        assertTrue(chapter.copy(sourceCount = 64).fitsSingleRequest)
        assertFalse(chapter.copy(sourceCount = 65).fitsSingleRequest)
        assertFalse(chapter.copy(sourceCount = 0).fitsSingleRequest)
        assertFalse(chapter.copy(start = start.copy(textOffset = end)).isValid)
        assertFalse(chapter.copy(depth = MAX_BOOK_TOC_DEPTH).isValid)
        assertTrue(BookStudyTask.SUMMARY.questionForChapter().contains("içindekiler bölümünün"))
        assertFalse(BookStudyTask.PRACTICE.questionForChapter().contains("EPUB dosyası"))
    }
}
