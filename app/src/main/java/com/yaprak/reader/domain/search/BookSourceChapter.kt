package com.yaprak.reader.domain.search

import java.util.Collections
import java.util.UUID

const val MAX_BOOK_TOC_ENTRIES = 5000
const val MAX_BOOK_TOC_DEPTH = 64

/** Coordinates in freshly extracted body text; never a DOM locator or a read-progress boundary. */
data class BookChapterBoundary(val readingOrderIndex: Int, val resourceHref: String,
    val resourceSha256: String, val textOffset: Int) {
    val isValid: Boolean get() = readingOrderIndex in 0 until MAX_INDEX_BOOK_RESOURCES &&
        safeLibraryTextHref(resourceHref) && resourceSha256.matches(Regex("[0-9a-f]{64}")) &&
        textOffset in 0..MAX_INDEX_RESOURCE_BYTES
}

data class BookSourceChapter(val scope: BookTextScope, val generation: String,
    val tocSha256: String, val tocIndex: Int, val title: String, val depth: Int,
    val start: BookChapterBoundary, val end: BookChapterBoundary, val sourceCount: Int) {
    val isValid: Boolean get() = scope.isValid &&
        runCatching { UUID.fromString(generation).toString() == generation }.getOrDefault(false) &&
        tocSha256.matches(Regex("[0-9a-f]{64}")) && tocIndex in 0 until MAX_BOOK_TOC_ENTRIES &&
        title.isNotBlank() && title.length <= 500 && title.none { it == '\u0000' } &&
        wellFormedChapterText(title) && depth in 0 until MAX_BOOK_TOC_DEPTH &&
        start.isValid && end.isValid && compareChapterBoundaries(start, end) < 0 &&
        (start.readingOrderIndex != end.readingOrderIndex ||
            (start.resourceHref == end.resourceHref && start.resourceSha256 == end.resourceSha256)) &&
        sourceCount in 0..MAX_INDEX_BOOK_CHUNKS
    val fitsSingleRequest: Boolean get() = isValid && sourceCount in 1..MAX_COMPLETE_BOOK_SOURCE_REFERENCES
}

fun compareChapterBoundaries(a: BookChapterBoundary, b: BookChapterBoundary): Int =
    a.readingOrderIndex.compareTo(b.readingOrderIndex).takeIf { it != 0 } ?: a.textOffset.compareTo(b.textOffset)

fun validBookSourceChapters(scope: BookTextScope, chapters: List<BookSourceChapter>): Boolean {
    if (!scope.isValid || chapters.size > MAX_BOOK_TOC_ENTRIES) return false
    var previous: BookSourceChapter? = null
    for (chapter in chapters) {
        val before = previous
        if (!chapter.isValid || chapter.scope != scope || (before != null &&
            (chapter.generation != before.generation || chapter.tocSha256 != before.tocSha256 ||
                chapter.tocIndex <= before.tocIndex || compareChapterBoundaries(before.start, chapter.start) > 0))) return false
        previous = chapter
    }
    return true
}

/** An exact slice of the original indexed hit, retaining the hit's identity for navigation. */
data class BookSourceExcerpt(val chunkTextOffset: Int, val startUtf16: Int, val endUtf16: Int) {
    fun isValid(text: String): Boolean = chunkTextOffset in 0..MAX_INDEX_RESOURCE_BYTES &&
        startUtf16 in 0 until text.length && endUtf16 in (startUtf16 + 1)..text.length &&
        chunkTextOffset.toLong() + endUtf16 <= MAX_INDEX_RESOURCE_BYTES &&
        !(startUtf16 > 0 && text[startUtf16].isLowSurrogate() && text[startUtf16 - 1].isHighSurrogate()) &&
        !(endUtf16 < text.length && text[endUtf16].isLowSurrogate() && text[endUtf16 - 1].isHighSurrogate())
}

fun validCompleteChapterSources(sources: PreparedBookSources): Boolean {
    if (sources.prefixQuery != null) return validPrefixQuerySources(sources)
    if (sources.prefix != null) return sources.chapter == null && validCompletePrefixSources(sources)
    val chapter = sources.chapter ?: return sources.references.all { it.excerpt == null }
    if (sources.section != null || !chapter.fitsSingleRequest || chapter.scope != sources.scope ||
        sources.query.isNotEmpty() || sources.totalMatches != chapter.sourceCount ||
        sources.references.size != chapter.sourceCount) return false
    var previous: BookSourceReference? = null
    for ((index, reference) in sources.references.withIndex()) {
        val hit = reference.hit
        val slice = reference.excerpt ?: return false
        if (reference.label != "S" + (index + 1) || hit.generation != chapter.generation || !slice.isValid(hit.text) ||
            hit.readingOrderIndex !in chapter.start.readingOrderIndex..chapter.end.readingOrderIndex ||
            reference.text.isBlank()) return false
        if (hit.readingOrderIndex == chapter.start.readingOrderIndex &&
            (hit.target.resourceHref != chapter.start.resourceHref || hit.target.resourceSha256 != chapter.start.resourceSha256 ||
                slice.chunkTextOffset + slice.startUtf16 < chapter.start.textOffset)) return false
        if (hit.readingOrderIndex == chapter.end.readingOrderIndex &&
            (hit.target.resourceHref != chapter.end.resourceHref || hit.target.resourceSha256 != chapter.end.resourceSha256 ||
                slice.chunkTextOffset + slice.endUtf16 > chapter.end.textOffset)) return false
        previous?.let { before ->
            if (before.hit.chunkIndex >= hit.chunkIndex || before.hit.readingOrderIndex > hit.readingOrderIndex) return false
            if (before.hit.readingOrderIndex == hit.readingOrderIndex &&
                (before.hit.target != hit.target || before.excerpt!!.chunkTextOffset + before.excerpt.endUtf16 >
                    slice.chunkTextOffset + slice.startUtf16)) return false
        }
        previous = reference
    }
    return true
}

/** Local extractor-only evidence. The adapter binds it to the validated cache generation. */
data class ExtractedChapter(val tocSha256: String, val tocIndex: Int, val title: String,
    val depth: Int, val start: BookChapterBoundary, val end: BookChapterBoundary, val sourceCount: Int)
data class ExtractedChapterPiece(val chunkIndex: Int, val excerpt: BookSourceExcerpt)

class ExtractedBookChapters(val document: ExtractedBookText, chapters: List<ExtractedChapter>,
    chunkTextOffsets: List<Int>) {
    val chapters: List<ExtractedChapter> = Collections.unmodifiableList(ArrayList(chapters))
    val chunkTextOffsets: List<Int> = Collections.unmodifiableList(ArrayList(chunkTextOffsets))
}

/** Uses only original chunk positions proven by the chapter extractor. No text normalization. */
fun extractedChapterPieces(evidence: ExtractedBookChapters, start: BookChapterBoundary,
    end: BookChapterBoundary, checkActive: () -> Unit = {}): List<ExtractedChapterPiece> {
    if (!start.isValid || !end.isValid || compareChapterBoundaries(start, end) >= 0 ||
        evidence.chunkTextOffsets.size != evidence.document.chunks.size) throw TextExtractionException(TextExtractionFailure.INVALID)
    val result = ArrayList<ExtractedChapterPiece>()
    var currentOrder = -1
    var covered = 0
    for ((index, chunk) in evidence.document.chunks.withIndex()) {
        checkActive()
        if (chunk.readingOrderIndex !in start.readingOrderIndex..end.readingOrderIndex) continue
        if (currentOrder != chunk.readingOrderIndex) {
            currentOrder = chunk.readingOrderIndex
            covered = if (currentOrder == start.readingOrderIndex) start.textOffset else 0
        }
        val offset = evidence.chunkTextOffsets[index]
        val from = maxOf(0, covered - offset)
        val until = minOf(chunk.text.length, if (currentOrder == end.readingOrderIndex) end.textOffset - offset else chunk.text.length)
        if (from >= until) continue
        val slice = BookSourceExcerpt(offset, from, until)
        if (!slice.isValid(chunk.text)) throw TextExtractionException(TextExtractionFailure.INVALID)
        covered = offset + until
        if (chunk.text.substring(from, until).isNotBlank()) result += ExtractedChapterPiece(chunk.chunkIndex, slice)
    }
    return Collections.unmodifiableList(result)
}

private fun wellFormedChapterText(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char.isLowSurrogate()) return false
        if (char.isHighSurrogate() && (index >= value.length || !value[index++].isLowSurrogate())) return false
    }
    return true
}
