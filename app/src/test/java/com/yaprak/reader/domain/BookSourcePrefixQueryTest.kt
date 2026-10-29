package com.yaprak.reader.domain

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.fixtures.PrefixAssistantFixtures as P
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class BookSourcePrefixQueryTest {
    private fun sources(query: String = "güneş", refs: List<BookSourceReference> = listOf(P.reference),
        total: Int = refs.size, count: Int = 65, chapter: BookSourceChapter = P.chapter) =
        PreparedBookSources(UUID.randomUUID().toString(), F.scope, query, total, refs,
            prefixQuery = BookSourcePrefixQuery(chapter, P.beginning, count))
    private fun refused(block: () -> Unit) {
        try { block(); fail("Invalid prefix query admitted") }
        catch (failure: BookAnswerException) { assertEquals(BookAnswerFailure.SOURCE, failure.reason) }
    }
    private fun proof(text: String, overlap: Boolean = false): ExtractedBookChapters {
        val chunks = if (overlap) listOf(text.substring(0, 2000), text.substring(1744)) else listOf(text)
        return ExtractedBookChapters(ExtractedBookText(F.book.id, F.book.sha256,
            chunks.mapIndexed { index, value -> ExtractedTextChunk(0, index, P.beginning.resourceHref,
                P.beginning.resourceSha256, null, value) }, 1), emptyList(), if (overlap) listOf(0, 1744) else listOf(0))
    }
    @Test fun queryCanUseLargeActualBoundaryWithoutAcquiringCompletePrefixOrFutureQuoteAuthority() {
        val source = sources()
        assertTrue(source.prefixQuery!!.isValid)
        assertFalse(P.prefix.copy(sourceCount = 65).fitsSingleRequest)
        val request = BookQuestionRequest.create(source, "Bu sınır içinde ne oldu?")
        assertEquals("Giriş. Güneş doğdu.", request.sources.references.single().text)
        assertTrue(request.sources.references.single().hit.text.contains("LATER_SECRET"))
        val answer = BookAnswer(source.requestId, BookAnswerStatus.ANSWERED,
            listOf(BookAnswerStatement("Sonrası", listOf(BookAnswerCitation(P.reference, "LATER_SECRET")))), "")
        try { validateBookAnswer(request, answer); fail("Future quote admitted") }
        catch (failure: BookAnswerException) { assertEquals(BookAnswerFailure.INVALID_RESPONSE, failure.reason) }
    }
    @Test fun rawFutureTermsCannotAuthorizeAClippedReferenceOrBecomeMatchingWindows() {
        refused { BookQuestionRequest.create(sources("LATER"), "Soru") }
        val plan = extractedPrefixQueryPieces(proof(P.raw), P.beginning, P.chapter.end, "LATER")
        assertEquals(1, plan.candidateCount); assertEquals(0, plan.totalMatches); assertTrue(plan.pieces.isEmpty())
        assertTrue(validPrefixQuerySources(sources("LATER", emptyList(), 0, 1)))
        refused { BookQuestionRequest.create(sources("LATER", emptyList(), 0, 1), "Soru") }
    }
    @Test fun queryNeedsItsOwnScopeAndNeverMixesWithCompleteOrArbitraryExcerptModes() {
        val scope = sources().prefixQuery!!
        val section = BookSourceSection(F.scope, P.generation, 0, P.beginning.resourceHref, P.beginning.resourceSha256,
            P.hit.chapterTitle, 0, 1)
        for (source in listOf(
            PreparedBookSources(UUID.randomUUID().toString(), F.scope, "güneş", 1, listOf(P.reference), prefix = P.prefix, prefixQuery = scope),
            PreparedBookSources(UUID.randomUUID().toString(), F.scope, "güneş", 1, listOf(P.reference), chapter = P.chapter, prefixQuery = scope),
            PreparedBookSources(UUID.randomUUID().toString(), F.scope, "güneş", 1, listOf(P.reference), section = section, prefixQuery = scope),
            PreparedBookSources(UUID.randomUUID().toString(), F.scope, "güneş", 1, listOf(P.reference)),
            sources("güneş", listOf(P.reference.copy(excerpt = null))),
        )) refused { BookQuestionRequest.create(source, "Soru") }
    }
    @Test fun fullMatchingCountRequiresAllEightFirstReferencesAndCannotBeAnArbitraryPartialPreview() {
        val chapter = P.chapter.copy(end = P.chapter.end.copy(textOffset = 1000))
        val refs = (0..7).map { index -> P.reference.copy(label = "S" + (index + 1),
            hit = P.hit.copy(id = index + 1L, chunkIndex = index),
            excerpt = BookSourceExcerpt(index * 30, 0, P.until)) }
        BookQuestionRequest.create(sources(refs = refs, total = 65, chapter = chapter), "Soru")
        refused { BookQuestionRequest.create(sources(refs = refs.dropLast(1), total = 65, chapter = chapter), "Soru") }
        refused { BookQuestionRequest.create(sources(refs = refs, total = 66, chapter = chapter), "Soru") }
        val ninth = refs.last().copy(label = "S9", hit = P.hit.copy(id = 9, chunkIndex = 8), excerpt = BookSourceExcerpt(240, 0, P.until))
        refused { BookQuestionRequest.create(sources(refs = refs + ninth, total = 65, chapter = chapter), "Soru") }
    }
    @Test fun forgedEndGenerationOrderAndRepeatedOriginalStartCannotBecomeSources() {
        for (source in listOf(
            sources(refs = listOf(P.reference.copy(excerpt = BookSourceExcerpt(0, 0, P.raw.length)))),
            sources(chapter = P.chapter.copy(generation = UUID.randomUUID().toString())),
            sources(chapter = P.chapter.copy(end = P.chapter.end.copy(textOffset = P.until - 1))),
            sources(refs = listOf(P.reference.copy(hit = P.hit.copy(target = P.hit.target.copy(contentSha256 = "d".repeat(64)))))),
        )) refused { BookQuestionRequest.create(source, "Soru") }
        val duplicateStart = P.reference.copy(label = "S2", hit = P.hit.copy(id = 2, chunkIndex = 1))
        refused { BookQuestionRequest.create(sources(refs = listOf(P.reference, duplicateStart)), "Soru") }
    }
    @Test fun localPolicyRequiresEveryTokenPrefixWithExistingTurkishFoldButNoSubstringOrDiacriticPromise() {
        assertTrue(prefixSourceKeywordsMatch("BAĞIMLILIK injection İSTANBUL", prefixSourceQueryTerms("bağ inj ıstan")))
        assertFalse(prefixSourceKeywordsMatch("heart injection", prefixSourceQueryTerms("art inj")))
        assertFalse(prefixSourceKeywordsMatch("bağımlılık", prefixSourceQueryTerms("bagim")))
        assertTrue(prefixSourceKeywordsMatch("injection dependency", prefixSourceQueryTerms("dependency, inject dependency")))
        for (query in listOf("", " ", "+++", "a".repeat(201), "a\u0000b", "\uD83C", (1..17).joinToString(" ") { "q$it" })) {
            try { prefixSourceQueryTerms(query); fail("Invalid query admitted") }
            catch (failure: BookSourceException) { assertEquals(BookSourceFailure.INVALID_QUERY, failure.reason) }
        }
    }
    @Test fun originalOverlapKeepsAQueryWhoseFirstTokenWouldBeLostByCompletePrefixDeduplication() {
        val raw = "z ".repeat(998) + "alpha beta"
        val evidence = proof(raw, true)
        val end = P.beginning.copy(textOffset = raw.length)
        val plan = extractedPrefixQueryPieces(evidence, P.beginning, end, "alpha beta")
        assertEquals(2, plan.candidateCount); assertEquals(1, plan.totalMatches)
        assertEquals(1, plan.pieces.single().chunkIndex); assertEquals(0, plan.pieces.single().excerpt.startUtf16)
        val deduplicated = extractedChapterPieces(evidence, P.beginning, end).last()
        val chunk = evidence.document.chunks.last().text
        assertFalse(prefixSourceKeywordsMatch(chunk.substring(deduplicated.excerpt.startUtf16, deduplicated.excerpt.endUtf16),
            prefixSourceQueryTerms("alpha beta")))
    }
    @Test fun sixtyFiveOriginalWindowsRetainOnlyEightMatchesAndKeepHonestTotal() {
        val chunks = (0..64).map { index -> ExtractedTextChunk(index, index, "EPUB/c$index.xhtml", "b".repeat(64), null, "needle") }
        val evidence = ExtractedBookChapters(ExtractedBookText(F.book.id, F.book.sha256, chunks, 65), emptyList(), List(65) { 0 })
        val start = BookChapterBoundary(0, chunks.first().resourceHref, chunks.first().resourceSha256, 0)
        val end = BookChapterBoundary(64, chunks.last().resourceHref, chunks.last().resourceSha256, 6)
        val plan = extractedPrefixQueryPieces(evidence, start, end, "needle")
        assertEquals(65, plan.candidateCount); assertEquals(65, plan.totalMatches)
        assertEquals((0..7).toList(), plan.pieces.map { it.chunkIndex })
    }
    @Test fun splitSurrogatesAreRejectedAndCallerCancellationEscapesUnchanged() {
        val raw = "alpha 🌿 beta"
        try { extractedPrefixQueryPieces(proof(raw), P.beginning, P.beginning.copy(textOffset = 7), "alpha"); fail("Split surrogate") }
        catch (failure: BookSourceException) { assertEquals(BookSourceFailure.SOURCE, failure.reason) }
        val cancelled = CancellationException("owned")
        assertSame(cancelled, runCatching { extractedPrefixQueryPieces(proof(raw), P.beginning,
            P.beginning.copy(textOffset = raw.length), "alpha") { throw cancelled } }.exceptionOrNull())
    }
}
