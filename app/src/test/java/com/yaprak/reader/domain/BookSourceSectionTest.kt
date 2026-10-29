package com.yaprak.reader.domain

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class BookSourceSectionTest {
    private val generation = UUID.randomUUID().toString()
    private val section = BookSourceSection(F.scope, generation, 2, F.hit.target.resourceHref,
        F.hit.target.resourceSha256, F.hit.chapterTitle, 3, 2)
    private fun sources(section: BookSourceSection = this.section, count: Int = section.chunkCount,
        query: String = "", references: List<BookSourceReference>? = null, total: Int = count) = PreparedBookSources(
        UUID.randomUUID().toString(), F.scope, query, total, references ?: (0 until count).map {
            BookSourceReference("S${it + 1}", F.hit.copy(id = it + 1L, generation = generation,
                readingOrderIndex = 2, chunkIndex = 3 + it))
        }, section)
    private fun refused(value: PreparedBookSources) {
        try { BookQuestionRequest.create(value, "Özetle"); fail("Accepted incomplete or wrong section") }
        catch (failure: BookAnswerException) { assertEquals(BookAnswerFailure.SOURCE, failure.reason) }
    }

    @Test fun `complete range is immutable request evidence and prompts only produce bounded drafts`() {
        val sources = sources()
        assertTrue(validCompleteSectionSources(sources)); assertFalse(sources.truncated)
        assertSame(sources, BookQuestionRequest.create(sources, BookStudyTask.SUMMARY.question).sources)
        assertTrue(BookStudyTask.values().all { validAnswerText(it.question, MAX_BOOK_QUESTION) })
        val mutable = sources.references.toMutableList()
        val detached = sources(references = mutable)
        mutable.clear(); assertEquals(2, detached.references.size)
        try { (detached.references as MutableList<*>).clear(); fail("Mutable sources") }
        catch (_: UnsupportedOperationException) { }
    }
    @Test fun `partial extra reordered and keyword subsets cannot claim a complete file`() {
        refused(sources(count = 1, total = 2)); refused(sources(count = 3)); refused(sources(query = "Güneş"))
        refused(sources(references = sources().references.reversed()))
        for (changed in listOf(section.copy(chunkCount = 3), section.copy(firstChunkIndex = 2),
            section.copy(generation = UUID.randomUUID().toString()), section.copy(readingOrderIndex = 1),
            section.copy(resourceHref = "EPUB/other.xhtml"), section.copy(resourceSha256 = "c".repeat(64)),
            section.copy(chapterTitle = "Başka başlık"), section.copy(scope = BookTextScope("other", F.book.sha256))))
            refused(sources(section = changed, references = sources().references))
        refused(sources(references = sources().references.mapIndexed { index, ref ->
            if (index == 0) ref.copy(label = "S9") else ref }))
    }
    @Test fun `malformed section identifiers types and request size never receive complete evidence`() {
        for (changed in listOf(section.copy(generation = "generation"), section.copy(readingOrderIndex = -1),
            section.copy(readingOrderIndex = MAX_INDEX_BOOK_RESOURCES), section.copy(resourceHref = "EPUB/a.xhtml#one"),
            section.copy(resourceHref = "../a.xhtml"), section.copy(resourceSha256 = "A".repeat(64)),
            section.copy(firstChunkIndex = -1), section.copy(firstChunkIndex = 9999),
            section.copy(chunkCount = 0), section.copy(chunkCount = Int.MAX_VALUE),
            section.copy(chapterTitle = "bad\uD800"), section.copy(chapterTitle = "bad\u0000")))
            assertFalse(changed.isValid)
        val large = section.copy(chunkCount = 65)
        assertTrue(large.isValid); assertFalse(large.fitsSingleRequest)
        refused(sources(large, count = 64, total = 65))
    }
    @Test fun `catalog allows empty resources and missing titles but requires complete chunk sequence and one generation`() {
        val first = section.copy(readingOrderIndex = 0, firstChunkIndex = 0, chapterTitle = null)
        val second = section.copy(readingOrderIndex = 2, firstChunkIndex = 2, resourceHref = "EPUB/second.xhtml")
        assertTrue(validBookSourceSections(F.scope, emptyList()))
        assertTrue(validBookSourceSections(F.scope, listOf(first, second)))
        for (wrong in listOf(second.copy(firstChunkIndex = 3), second.copy(readingOrderIndex = 0),
            second.copy(resourceHref = first.resourceHref), second.copy(generation = UUID.randomUUID().toString()),
            second.copy(scope = BookTextScope("other", F.scope.contentSha256))))
            assertFalse(validBookSourceSections(F.scope, listOf(first, wrong)))
        assertFalse(validBookSourceSections(F.scope, listOf(second, first)))
    }
    @Test fun `legacy keyword and passage requests retain their original contract`() {
        val original = F.sources()
        assertNull(original.section); assertTrue(validCompleteSectionSources(original))
        assertSame(original, BookQuestionRequest.create(original, "Ne oldu?").sources)
        assertTrue(original.query.isNotEmpty())
    }
}
