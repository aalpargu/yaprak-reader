package com.yaprak.reader.domain.search

import com.yaprak.reader.domain.library.BookFormat

/** Original reading-order text through an explicit actual TOC boundary, including earlier preface.
 * Does not assert what the user has read or where a story's future events occur.
 */
data class BookSourcePrefix(val throughChapter: BookSourceChapter, val start: BookChapterBoundary,
    val sourceCount: Int) {
    val isValid: Boolean get() = throughChapter.isValid && start.isValid && start.textOffset == 0 &&
        compareChapterBoundaries(start, throughChapter.end) < 0 &&
        (start.readingOrderIndex != throughChapter.start.readingOrderIndex ||
            start.resourceHref == throughChapter.start.resourceHref && start.resourceSha256 == throughChapter.start.resourceSha256) &&
        sourceCount in 1..MAX_INDEX_BOOK_CHUNKS
    val fitsSingleRequest: Boolean get() = isValid && sourceCount <= MAX_COMPLETE_BOOK_SOURCE_REFERENCES
}

/** Validates bounded slices; only the local adapter's fresh full extraction establishes completeness. */
fun validCompletePrefixSources(sources: PreparedBookSources): Boolean {
    val prefix = sources.prefix ?: return true
    val chapter = prefix.throughChapter
    if (sources.section != null || sources.chapter != null || sources.prefixQuery != null || !prefix.fitsSingleRequest ||
        chapter.scope != sources.scope || sources.query.isNotEmpty() ||
        sources.totalMatches != prefix.sourceCount || sources.references.size != prefix.sourceCount) return false
    var previous: BookSourceReference? = null
    for ((index, reference) in sources.references.withIndex()) {
        val hit = reference.hit
        val slice = reference.excerpt ?: return false
        if (reference.label != "S" + (index + 1) || hit.generation != chapter.generation ||
            hit.book.format != BookFormat.EPUB || hit.target.sourceFormat != BookFormat.EPUB ||
            hit.book.id != sources.scope.bookId || hit.book.sha256 != sources.scope.contentSha256 ||
            hit.target.bookId != sources.scope.bookId || hit.target.contentSha256 != sources.scope.contentSha256 ||
            !slice.isValid(hit.text) || reference.text.isBlank() ||
            hit.readingOrderIndex !in prefix.start.readingOrderIndex..chapter.end.readingOrderIndex) return false
        if (index == 0 && (hit.chunkIndex != 0 || hit.readingOrderIndex != prefix.start.readingOrderIndex ||
                hit.target.resourceHref != prefix.start.resourceHref || hit.target.resourceSha256 != prefix.start.resourceSha256 ||
                slice.chunkTextOffset != 0 || slice.startUtf16 != 0)) return false
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
