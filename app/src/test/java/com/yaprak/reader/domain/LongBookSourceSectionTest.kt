package com.yaprak.reader.domain

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LongBookSourceSectionTest {
    private fun sources(count: Int = 64, complete: Boolean = true): PreparedBookSources {
        val generation = UUID.randomUUID().toString()
        val section = BookSourceSection(F.scope, generation, 0, F.hit.target.resourceHref,
            F.hit.target.resourceSha256, F.hit.chapterTitle, 0, count)
        return PreparedBookSources(UUID.randomUUID().toString(), F.scope, if (complete) "" else "Güneş", count,
            (0 until count).map { index ->
                BookSourceReference("S" + (index + 1), F.hit.copy(id = index + 1L, generation = generation,
                    chunkIndex = index, text = "Kaynak metni $index. Güneş doğdu."))
            }, if (complete) section else null)
    }
    private fun refused(sources: PreparedBookSources) {
        try { BookQuestionRequest.create(sources, "Özetle"); fail("Accepted invalid source range") }
        catch (failure: BookAnswerException) { assertEquals(BookAnswerFailure.SOURCE, failure.reason) }
    }
    @Test fun completeSixtyFourSourcesAreAdmittedButSixtyFiveAreNot() {
        val sources = sources()
        assertTrue(sources.section!!.fitsSingleRequest)
        assertEquals(64, BookQuestionRequest.create(sources, BookStudyTask.SUMMARY.question).sources.references.size)
        assertFalse(sources(65).section!!.fitsSingleRequest); refused(sources(65))
        assertTrue(sources(9).section!!.fitsSingleRequest)
    }
    @Test fun keywordLimitStaysEightAndCompleteMetadataCannotAuthorizeKeywordSubset() {
        assertEquals(8, BookQuestionRequest.create(sources(8, false), "Soru").sources.references.size)
        refused(sources(9, false))
        val full = sources()
        refused(PreparedBookSources(full.requestId, full.scope, "Güneş", full.totalMatches, full.references, full.section))
        refused(PreparedBookSources(full.requestId, full.scope, "", full.totalMatches, full.references.take(63), full.section))
    }
    @Test fun lastSourceChangesAndReorderingRefuseWholeResourceEvidence() {
        val full = sources()
        val last = full.references.last()
        for (changed in listOf(last.copy(hit = last.hit.copy(chunkIndex = 62)),
            last.copy(hit = last.hit.copy(generation = UUID.randomUUID().toString())),
            last.copy(hit = last.hit.copy(target = last.hit.target.copy(resourceHref = "EPUB/other.xhtml"))),
            last.copy(label = "S65"))) {
            refused(PreparedBookSources(full.requestId, full.scope, "", 64, full.references.dropLast(1) + changed, full.section))
        }
        refused(PreparedBookSources(full.requestId, full.scope, "", 64, full.references.reversed(), full.section))
    }
    @Test fun citationsFromLastSourceBindToTheRequestAndUnknownLastLabelRefuses() {
        val request = BookQuestionRequest.create(sources(), "Son kaynağı açıkla")
        val source = request.sources.references.last()
        val citation = BookAnswerCitation(source, source.hit.text)
        val answer = BookAnswer(request.sources.requestId, BookAnswerStatus.ANSWERED,
            listOf(BookAnswerStatement("Son parça için açıklama.", listOf(citation))), "")
        validateBookAnswer(request, answer)
        val wrong = BookAnswer(request.sources.requestId, BookAnswerStatus.ANSWERED,
            listOf(BookAnswerStatement("Son parça.", listOf(citation.copy(source = source.copy(label = "S65"))))), "")
        try { validateBookAnswer(request, wrong); fail("Unknown last label") }
        catch (failure: BookAnswerException) { assertEquals(BookAnswerFailure.INVALID_RESPONSE, failure.reason) }
    }
}

