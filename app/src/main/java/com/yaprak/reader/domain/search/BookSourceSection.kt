package com.yaprak.reader.domain.search

import java.util.UUID
import com.yaprak.reader.domain.library.BookFormat

/** A complete, nonempty EPUB XHTML resource, extracted PDF page, canonical TXT/FB2 part, or complete extracted DOCX document text.
 * It is not a TOC fragment, a numbered chapter, a read-prefix, or a spoiler guarantee.
 * Generation and the entire chunk range prevent an old selection from retargeting a new index.
 */
data class BookSourceSection(
    val scope: BookTextScope,
    val generation: String,
    val readingOrderIndex: Int,
    val resourceHref: String,
    val resourceSha256: String,
    val chapterTitle: String?,
    val firstChunkIndex: Int,
    val chunkCount: Int,
    val sourceFormat: BookFormat = BookFormat.EPUB,
    val pdfPageIndex: Int? = null,
) {
    val isValid: Boolean get() = scope.isValid && canonicalSourceGeneration(generation) &&
        readingOrderIndex in 0 until MAX_INDEX_BOOK_RESOURCES &&
        LibraryTextTarget(scope.bookId, scope.contentSha256, resourceHref, resourceSha256,
            sourceFormat = sourceFormat, pdfPageIndex = pdfPageIndex).isValid &&
        when (sourceFormat) {
            BookFormat.EPUB -> true
            BookFormat.PDF -> pdfPageIndex == readingOrderIndex && chapterTitle == null
            BookFormat.TXT -> txtLibraryTextPartIndex(resourceHref) == readingOrderIndex &&
                chapterTitle == "Metin " + (readingOrderIndex + 1)
            BookFormat.FB2 -> chapterTitle != null && chapterTitle.isNotBlank()
            BookFormat.DOCX -> readingOrderIndex == 0 && firstChunkIndex == 0 && chapterTitle == "Belge metni"
            BookFormat.CBZ, BookFormat.CBR -> false
        } &&
        (chapterTitle == null || validSourceTitle(chapterTitle)) &&
        firstChunkIndex in 0 until MAX_INDEX_BOOK_CHUNKS && chunkCount in 1..MAX_INDEX_BOOK_CHUNKS &&
        firstChunkIndex.toLong() + chunkCount <= MAX_INDEX_BOOK_CHUNKS
    val fitsSingleRequest: Boolean get() = isValid && chunkCount <= MAX_COMPLETE_BOOK_SOURCE_REFERENCES
}

fun validBookSourceSections(scope: BookTextScope, sections: List<BookSourceSection>): Boolean {
    if (!scope.isValid || sections.size > MAX_INDEX_BOOK_RESOURCES) return false
    var nextChunk = 0
    var lastOrder = -1
    var generation: String? = null
    val resources = mutableSetOf<Pair<String, Int?>>()
    var format: BookFormat? = null
    for (section in sections) {
        if (!section.isValid || section.scope != scope || section.readingOrderIndex <= lastOrder ||
            section.firstChunkIndex != nextChunk || !resources.add(section.resourceHref to section.pdfPageIndex) ||
            (format != null && format != section.sourceFormat) ||
            (generation != null && generation != section.generation)) return false
        generation = section.generation
        format = section.sourceFormat
        lastOrder = section.readingOrderIndex
        nextChunk += section.chunkCount
    }
    return true
}

/** Boundary validation is stricter for complete resources than for keyword hits.
 * Completeness is established by the local adapter's fresh extraction; the network cannot grant it.
 */
fun validCompleteSectionSources(sources: PreparedBookSources): Boolean {
    val section = sources.section ?: return true
    if (!section.fitsSingleRequest || section.scope != sources.scope || sources.query.isNotEmpty() ||
        sources.totalMatches != section.chunkCount || sources.references.size != section.chunkCount) return false
    return sources.references.withIndex().all { (index, reference) ->
        val hit = reference.hit
        reference.label == "S${index + 1}" && hit.generation == section.generation &&
            hit.readingOrderIndex == section.readingOrderIndex && hit.chunkIndex == section.firstChunkIndex + index &&
            hit.target.resourceHref == section.resourceHref && hit.target.resourceSha256 == section.resourceSha256 &&
            hit.target.sourceFormat == section.sourceFormat && hit.book.format == section.sourceFormat &&
            hit.target.pdfPageIndex == section.pdfPageIndex && reference.excerpt == null &&
            hit.chapterTitle == section.chapterTitle
    }
}

private fun canonicalSourceGeneration(value: String): Boolean =
    try { UUID.fromString(value).toString() == value } catch (_: Exception) { false }

private fun validSourceTitle(value: String): Boolean {
    if (value.length > MAX_INDEX_CHAPTER_TITLE) return false
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char == '\u0000' || char.isLowSurrogate()) return false
        if (char.isHighSurrogate() && (index == value.length || !value[index++].isLowSurrogate())) return false
    }
    return true
}
