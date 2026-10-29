package com.yaprak.reader.domain.search

import com.yaprak.reader.domain.library.BookFormat
import java.util.Collections

private val prefixSourceTokens = Regex("[\\p{L}\\p{M}\\p{N}]+")

/** Explicit actual EPUB beginning/end plus an honest count of original candidate windows.
 * This is keyword retrieval, never completeness, reading history, or narrative spoiler authority.
 */
data class BookSourcePrefixQuery(val throughChapter: BookSourceChapter, val start: BookChapterBoundary,
    val candidateCount: Int) {
    val isValid: Boolean get() = BookSourcePrefix(throughChapter, start, candidateCount).isValid
}

/** The existing search fold is shared; matching itself is an explicit local token-prefix policy,
 * not a claim of SQLite tokenizer equivalence or semantic retrieval.
 */
internal fun prefixSourceQueryTerms(query: String): List<String> {
    var index = 0
    if (query.length > MAX_LIBRARY_TEXT_QUERY || query.isBlank()) throw BookSourceException(BookSourceFailure.INVALID_QUERY)
    while (index < query.length) {
        val char = query[index++]
        if (char == '\u0000' || char.isLowSurrogate() ||
            char.isHighSurrogate() && (index == query.length || !query[index++].isLowSurrogate()))
            throw BookSourceException(BookSourceFailure.INVALID_QUERY)
    }
    val terms = prefixSourceTokens.findAll(libraryTextSearchKey(query)).map { it.value }.distinct().toList()
    if (terms.isEmpty() || terms.size > MAX_LIBRARY_TEXT_QUERY_TERMS)
        throw BookSourceException(BookSourceFailure.INVALID_QUERY)
    return terms
}

internal fun prefixSourceKeywordsMatch(text: String, terms: List<String>): Boolean {
    val tokens = prefixSourceTokens.findAll(libraryTextSearchKey(text)).map { it.value }.toList()
    return terms.isNotEmpty() && terms.all { term -> tokens.any { it.startsWith(term) } }
}

internal class PrefixQueryPieces(val candidateCount: Int, val totalMatches: Int, pieces: List<ExtractedChapterPiece>) {
    val pieces: List<ExtractedChapterPiece> = Collections.unmodifiableList(ArrayList(pieces))
}

/** Clip BEFORE matching. Keep original overlaps so a cross-chunk query can still have context.
 * Count matching windows, not word occurrences, and retain only the first eight in book order.
 */
internal fun extractedPrefixQueryPieces(evidence: ExtractedBookChapters, start: BookChapterBoundary,
    end: BookChapterBoundary, query: String, checkActive: () -> Unit = {}): PrefixQueryPieces {
    val terms = prefixSourceQueryTerms(query)
    if (!start.isValid || !end.isValid || start.textOffset != 0 || compareChapterBoundaries(start, end) >= 0 ||
        evidence.document.sourceFormat != BookFormat.EPUB || evidence.document.chunks.size > MAX_INDEX_BOOK_CHUNKS ||
        evidence.chunkTextOffsets.size != evidence.document.chunks.size) throw BookSourceException(BookSourceFailure.SOURCE)
    var candidates = 0
    var matches = 0
    val pieces = ArrayList<ExtractedChapterPiece>()
    for ((index, chunk) in evidence.document.chunks.withIndex()) {
        checkActive()
        if (chunk.readingOrderIndex !in start.readingOrderIndex..end.readingOrderIndex) continue
        if (chunk.chunkIndex != index || chunk.sourceFormat != BookFormat.EPUB) throw BookSourceException(BookSourceFailure.SOURCE)
        val offset = evidence.chunkTextOffsets[index]
        val from = if (chunk.readingOrderIndex == start.readingOrderIndex) maxOf(0, start.textOffset - offset) else 0
        val until = minOf(chunk.text.length,
            if (chunk.readingOrderIndex == end.readingOrderIndex) end.textOffset - offset else chunk.text.length)
        if (from >= until) continue
        val excerpt = BookSourceExcerpt(offset, from, until)
        if (!excerpt.isValid(chunk.text)) throw BookSourceException(BookSourceFailure.SOURCE)
        val admitted = chunk.text.substring(from, until)
        if (admitted.isBlank()) continue
        candidates++
        if (prefixSourceKeywordsMatch(admitted, terms)) {
            matches++
            if (pieces.size < MAX_BOOK_SOURCE_REFERENCES) pieces += ExtractedChapterPiece(chunk.chunkIndex, excerpt)
        }
    }
    if (candidates == 0) throw BookSourceException(BookSourceFailure.UNSUPPORTED)
    return PrefixQueryPieces(candidates, matches, pieces)
}

/** Structural and matching validation; only fresh original extraction in the local adapter proves the scope. */
fun validPrefixQuerySources(sources: PreparedBookSources): Boolean {
    val chosen = sources.prefixQuery ?: return true
    val chapter = chosen.throughChapter
    val terms = try { prefixSourceQueryTerms(sources.query) } catch (_: BookSourceException) { return false }
    if (sources.section != null || sources.chapter != null || sources.prefix != null || !chosen.isValid ||
        chapter.scope != sources.scope || sources.totalMatches !in 0..chosen.candidateCount ||
        sources.references.size != minOf(sources.totalMatches, MAX_BOOK_SOURCE_REFERENCES)) return false
    var previous: BookSourceReference? = null
    for ((index, reference) in sources.references.withIndex()) {
        val hit = reference.hit
        val slice = reference.excerpt ?: return false
        if (reference.label != "S" + (index + 1) || hit.generation != chapter.generation ||
            hit.book.format != BookFormat.EPUB || hit.target.sourceFormat != BookFormat.EPUB ||
            hit.book.id != sources.scope.bookId || hit.book.sha256 != sources.scope.contentSha256 ||
            hit.target.bookId != sources.scope.bookId || hit.target.contentSha256 != sources.scope.contentSha256 ||
            !slice.isValid(hit.text) || reference.text.isBlank() || !prefixSourceKeywordsMatch(reference.text, terms) ||
            hit.readingOrderIndex !in chosen.start.readingOrderIndex..chapter.end.readingOrderIndex) return false
        if (hit.readingOrderIndex == chosen.start.readingOrderIndex &&
            (hit.target.resourceHref != chosen.start.resourceHref || hit.target.resourceSha256 != chosen.start.resourceSha256 ||
                slice.chunkTextOffset + slice.startUtf16 < chosen.start.textOffset)) return false
        if (hit.readingOrderIndex == chapter.end.readingOrderIndex &&
            (hit.target.resourceHref != chapter.end.resourceHref || hit.target.resourceSha256 != chapter.end.resourceSha256 ||
                slice.chunkTextOffset + slice.endUtf16 > chapter.end.textOffset)) return false
        previous?.let { before ->
            if (before.hit.chunkIndex >= hit.chunkIndex || before.hit.readingOrderIndex > hit.readingOrderIndex ||
                before.hit.readingOrderIndex == hit.readingOrderIndex &&
                    (before.hit.target != hit.target || before.excerpt!!.chunkTextOffset + before.excerpt.startUtf16 >=
                        slice.chunkTextOffset + slice.startUtf16)) return false
        }
        previous = reference
    }
    return true
}
