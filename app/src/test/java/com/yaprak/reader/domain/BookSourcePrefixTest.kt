package com.yaprak.reader.domain

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.fixtures.PrefixAssistantFixtures as P
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class BookSourcePrefixTest {
    private fun refused(block: ()->Unit) {
        try { block();fail("Invalid prefix admitted") } catch (failure: BookAnswerException) { assertEquals(BookAnswerFailure.SOURCE,failure.reason) }
    }
    @Test fun beginningIncludesPrefaceButFutureTextCannotBeQuotedAsAdmittedEvidence() {
        val request=BookQuestionRequest.create(P.sources(),"Bu sınıra kadar ne oldu?")
        assertEquals("Giriş. Güneş doğdu.",request.sources.references.single().text)
        assertTrue(request.sources.references.single().hit.text.contains("LATER_SECRET"))
        val allowed=BookAnswer(request.sources.requestId,BookAnswerStatus.ANSWERED,
            listOf(BookAnswerStatement("Giriş var.",listOf(BookAnswerCitation(P.reference,"Giriş.")))),"")
        validateBookAnswer(request,allowed)
        try { validateBookAnswer(request,BookAnswer(request.sources.requestId,BookAnswerStatus.ANSWERED,
            listOf(BookAnswerStatement("Sonrası.",listOf(BookAnswerCitation(P.reference,"LATER_SECRET")))),""));fail("Future quote") }
        catch(failure: BookAnswerException) { assertEquals(BookAnswerFailure.INVALID_RESPONSE,failure.reason) }
        assertTrue(BookStudyTask.SUMMARY.questionForPrefix().contains("kitabın başından"))
    }
    @Test fun prefixChapterResourceAndKeywordScopesCannotBeMixedOrUsedAsArbitraryExcerptPermission() {
        val section=BookSourceSection(F.scope,P.generation,0,P.beginning.resourceHref,P.beginning.resourceSha256,F.hit.chapterTitle,0,1)
        for(sources in listOf(
            PreparedBookSources(UUID.randomUUID().toString(),F.scope,"",1,listOf(P.reference),chapter=P.chapter,prefix=P.prefix),
            PreparedBookSources(UUID.randomUUID().toString(),F.scope,"",1,listOf(P.reference),section=section,prefix=P.prefix),
            PreparedBookSources(UUID.randomUUID().toString(),F.scope,"keyword",1,listOf(P.reference),prefix=P.prefix),
            PreparedBookSources(UUID.randomUUID().toString(),F.scope,"keyword",1,listOf(P.reference))))
            refused { BookQuestionRequest.create(sources,"Soru") }
    }
    @Test fun forgedBeginningEndGenerationCountFormatAndSurrogateBoundariesFailClosed() {
        for(prefix in listOf(P.prefix.copy(start=P.beginning.copy(textOffset=1)),P.prefix.copy(sourceCount=2),
            P.prefix.copy(start=P.beginning.copy(resourceSha256="d".repeat(64))),
            P.prefix.copy(throughChapter=P.chapter.copy(generation=UUID.randomUUID().toString())),
            P.prefix.copy(throughChapter=P.chapter.copy(end=P.chapter.end.copy(textOffset=P.until-1)))))
            refused { BookQuestionRequest.create(P.sources(prefix),"Soru") }
        for(reference in listOf(P.reference.copy(excerpt=BookSourceExcerpt(0,1,P.until)),
            P.reference.copy(excerpt=BookSourceExcerpt(0,0,P.raw.length)),
            P.reference.copy(hit=P.hit.copy(book=F.book.copy(format=BookFormat.PDF))),
            P.reference.copy(hit=P.hit.copy(target=P.hit.target.copy(sourceFormat=BookFormat.PDF,pdfPageIndex=0))),
            P.reference.copy(hit=P.hit.copy(text="🌿"+P.raw),excerpt=BookSourceExcerpt(0,0,1)),
            P.reference.copy(hit=P.hit.copy(chunkIndex=1))))
            refused { BookQuestionRequest.create(P.sources(refs=listOf(reference)),"Soru") }
    }
    @Test fun fullPrefixHasASeparateSixtyFourSourceBoundAndNeverAcquiresKeywordAuthority() {
        assertTrue(P.prefix.copy(sourceCount=64).fitsSingleRequest)
        assertFalse(P.prefix.copy(sourceCount=65).fitsSingleRequest)
        assertFalse(P.prefix.copy(sourceCount=0).isValid)
        assertFalse(P.prefix.copy(start=P.beginning.copy(textOffset=P.until)).isValid)
        assertTrue(P.prefix.copy(start=BookChapterBoundary(1,"EPUB/first-text.xhtml","d".repeat(64),0),
            throughChapter=P.chapter.copy(end=BookChapterBoundary(1,"EPUB/first-text.xhtml","d".repeat(64),20))).isValid)
    }
    @Test fun overlappingOriginalChunksAreSlicedFromBeginningThroughExactBoundaryWithoutFutureTail() {
        val raw="a".repeat(1744)+"🌿ADMITTED"+"b".repeat(1200)+"FUTURE"
        val chunks=listOf(ExtractedTextChunk(0,0,P.beginning.resourceHref,P.beginning.resourceSha256,null,raw.substring(0,2000)),
            ExtractedTextChunk(0,1,P.beginning.resourceHref,P.beginning.resourceSha256,null,raw.substring(1744)))
        val proof=ExtractedBookChapters(ExtractedBookText(F.book.id,F.book.sha256,chunks,1),emptyList(),listOf(0,1744))
        val pieces=extractedChapterPieces(proof,P.beginning,P.beginning.copy(textOffset=2300))
        assertEquals(raw.substring(0,2300),pieces.joinToString("") { chunks[it.chunkIndex].text.substring(it.excerpt.startUtf16,it.excerpt.endUtf16) })
        assertEquals(256,pieces.last().excerpt.startUtf16)
    }
}
