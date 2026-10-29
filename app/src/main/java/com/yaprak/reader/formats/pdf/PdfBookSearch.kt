package com.yaprak.reader.formats.pdf

import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.reader.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val MAX_PDF_PAGE_TEXT = 512 * 1024
internal const val MAX_PDF_TEXT_BLOCKS = 10_000
private const val MAX_PDF_SCAN_TEXT = 32L * 1024 * 1024
internal data class PdfExtractedText(val text: String, val rawUtf16: Int)

/** The complete open PDF is searched locally. A result opens its page, never an invented glyph anchor. */
internal class PdfBookSearch(private val document: PdfDocument, private val navigate: (Int) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO) : BookTextSearch {
    override val kind = BookSearchKind.PDF
    override val supported get() = document.textSupported
    private val searchMutex = Mutex()
    override suspend fun find(query: String): BookSearchResults = withContext(io) {
        searchMutex.withLock {
            if (!document.isOpen) throw BookSearchException(BookSearchFailure.SOURCE)
            if (!supported) throw BookSearchException(BookSearchFailure.UNSUPPORTED)
            if (query.length !in 1..200 || !validPdfText(query)) throw BookSearchException(BookSearchFailure.INVALID_QUERY)
            val needle = normalizePdfWhitespace(query).trim()
            if (needle.isEmpty()) throw BookSearchException(BookSearchFailure.INVALID_QUERY)
            val hits = ArrayList<BookSearchHit>()
            var scanned = 0L
            for (page in 0 until document.pageCount) {
                currentCoroutineContext().ensureActive()
                val extracted = document.extractText(page)
                currentCoroutineContext().ensureActive()
                scanned += extracted.rawUtf16
                if (scanned > MAX_PDF_SCAN_TEXT) throw BookSearchException(BookSearchFailure.LIMIT)
                val text = extracted.text
                var offset = 0
                while (offset <= text.length - needle.length) {
                    currentCoroutineContext().ensureActive()
                    val match = text.indexOf(needle, offset, ignoreCase = true)
                    if (match < 0) break
                    if (hits.size == 200) return@withLock BookSearchResults(hits.toList(), true)
                    hits += BookSearchHit("Sayfa ${page + 1}", pdfSearchSnippet(text, match, match + needle.length), PdfPosition.encode(page))
                    offset = match + needle.length
                }
            }
            currentCoroutineContext().ensureActive()
            BookSearchResults(hits.toList(), false)
        }
    }
    override fun goTo(location: ReadingLocation): Boolean {
        if (!document.isOpen) return false
        val page = PdfPosition.decodeOrNull(location, document.pageCount) ?: return false
        return navigate(page)
    }
}

/** Native returns all blocks before this check; limits bound the retained snapshot, not native internals. */
internal fun boundedPdfText(blocks: List<String>): PdfExtractedText {
    if (blocks.size > MAX_PDF_TEXT_BLOCKS) throw BookSearchException(BookSearchFailure.LIMIT)
    var raw = 0L
    blocks.forEach { block ->
        raw += block.length.toLong() + 1
        if (raw > MAX_PDF_PAGE_TEXT) throw BookSearchException(BookSearchFailure.LIMIT)
        if (!validPdfText(block)) throw BookSearchException(BookSearchFailure.SOURCE)
    }
    return PdfExtractedText(normalizePdfWhitespace(blocks.joinToString("\n")), raw.toInt())
}

internal fun validPdfText(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val c = text[index++]
        if (c.isHighSurrogate()) {
            if (index >= text.length || !text[index++].isLowSurrogate()) return false
        } else if (c.isLowSurrogate() || c == '\u0000') return false
    }
    return true
}

internal fun normalizePdfWhitespace(text: String): String = buildString(text.length) {
    var space = false
    for (c in text) {
        if (c.isWhitespace() || Character.isSpaceChar(c)) { space = true }
        else {
            if (space && isNotEmpty()) append(' ')
            append(c); space = false
        }
    }
}

internal fun pdfSearchSnippet(text: String, start: Int, end: Int): String {
    var from = (start - 80).coerceAtLeast(0)
    var to = (end + 80).coerceAtMost(text.length)
    if (from > 0 && text[from].isLowSurrogate()) from--
    if (to < text.length && text[to - 1].isHighSurrogate()) to++
    return (if (from > 0) "…" else "") + text.substring(from, to) + if (to < text.length) "…" else ""
}
