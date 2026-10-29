package com.yaprak.reader.domain.search

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import java.net.URI
import java.util.Locale
import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

const val LIBRARY_TEXT_EXTRACTION_VERSION = 1
const val MAX_LIBRARY_TEXT_QUERY = 200
const val MAX_LIBRARY_TEXT_QUERY_TERMS = 16
const val MAX_LIBRARY_TEXT_RESULTS = 200
const val MAX_INDEX_RESOURCE_BYTES = 2 * 1024 * 1024
const val MAX_INDEX_BOOK_RESOURCES = 1000
const val MAX_INDEX_CHUNK_TEXT = 2000
const val MAX_INDEX_CHAPTER_TITLE = 4096
const val INDEX_CHUNK_OVERLAP = 256
const val MAX_INDEX_BOOK_CHUNKS = 10_000
const val MAX_INDEX_BOOK_STRING_BYTES = 16L * 1024 * 1024
const val MAX_INDEX_LIBRARY_CHUNKS = 250_000
const val MAX_INDEX_LIBRARY_STRING_BYTES = 256L * 1024 * 1024

/** EPUB: original XHTML hash and chapter. PDF: complete extracted page text hash and real page.
 * TXT/FB2: full canonical original text-part hash and a generated viewing-part address.
 * The file hash is independent. Neither address grants an exact text/glyph selection anchor.
 */
data class LibraryTextTarget(
    val bookId: String,
    val contentSha256: String,
    val resourceHref: String,
    val resourceSha256: String,
    val extractionVersion: Int = LIBRARY_TEXT_EXTRACTION_VERSION,
    val sourceFormat: BookFormat = BookFormat.EPUB,
    val pdfPageIndex: Int? = null,
) {
    val isValid: Boolean get() = bookId.isNotBlank() && bookId.length <= 200 &&
        bookId.none { it.isISOControl() || it.isSurrogate() } &&
        contentSha256.matches(TEXT_HASH) && resourceSha256.matches(TEXT_HASH) &&
        extractionVersion == LIBRARY_TEXT_EXTRACTION_VERSION && when (sourceFormat) {
            BookFormat.EPUB -> pdfPageIndex == null && safeLibraryTextHref(resourceHref)
            BookFormat.TXT -> pdfPageIndex == null && txtLibraryTextPartIndex(resourceHref) != null
            BookFormat.PDF -> resourceHref.isEmpty() && pdfPageIndex != null &&
                pdfPageIndex in 0 until MAX_INDEX_BOOK_RESOURCES
            BookFormat.FB2 -> pdfPageIndex == null && validFb2LibraryTextHref(resourceHref)
            BookFormat.DOCX -> pdfPageIndex == null && resourceHref == "Text/document.xhtml"
            BookFormat.CBZ, BookFormat.CBR -> false
        }
}

data class ExtractedTextChunk(
    val readingOrderIndex: Int,
    val chunkIndex: Int,
    val resourceHref: String,
    val resourceSha256: String,
    val chapterTitle: String?,
    /** Original decoded body text. Block separators are extraction delimiters, not source offsets. */
    val text: String,
    val sourceFormat: BookFormat = BookFormat.EPUB,
    val pdfPageIndex: Int? = null,
)

class ExtractedBookText(
    val bookId: String,
    val contentSha256: String,
    chunks: List<ExtractedTextChunk>,
    val resourceCount: Int,
    val extractionVersion: Int = LIBRARY_TEXT_EXTRACTION_VERSION,
    val sourceFormat: BookFormat = BookFormat.EPUB,
) {
    val chunks: List<ExtractedTextChunk> = Collections.unmodifiableList(ArrayList(chunks))
}

/** Implementations either return the entire supported reading order or throw; no partial success. */
fun interface BookTextExtractor {
    suspend fun extract(book: Book): ExtractedBookText
    suspend fun extractChapters(book: Book): ExtractedBookChapters =
        throw TextExtractionException(TextExtractionFailure.UNSUPPORTED)
    /** Admission is also checked when reading an existing cache on a less capable device. */
    fun supports(book: Book): Boolean = book.format == BookFormat.EPUB
}
enum class TextExtractionFailure { UNSUPPORTED, INVALID, SOURCE, LIMIT }
class TextExtractionException(val reason: TextExtractionFailure) : Exception(reason.name)

enum class LibraryTextBookState { NOT_INDEXED, READY, UNSUPPORTED, FAILED, LIMIT, NO_TEXT }
data class LibraryTextBookStatus(val book: Book, val state: LibraryTextBookState, val chunkCount: Int = 0)
data class LibraryTextIndexProgress(val processed: Int, val total: Int, val currentBookTitle: String?)
data class LibraryTextHit(
    val id: Long,
    val generation: String,
    val book: Book,
    val target: LibraryTextTarget,
    val chapterTitle: String?,
    val text: String,
    val snippet: String,
    val readingOrderIndex: Int = -1,
    val chunkIndex: Int = -1,
)
data class LibraryTextSearchResult(
    val query: String,
    val hits: List<LibraryTextHit>,
    val totalMatches: Int,
    val books: List<LibraryTextBookStatus>,
    val scope: BookTextScope? = null,
) {
    val truncated: Boolean get() = totalMatches > hits.size
}

interface LibraryTextIndex {
    fun observe(query: String): Flow<LibraryTextSearchResult>
    /** Restricts SQLite before count/result limits. Unsupported implementations fail closed. */
    fun observeBook(query: String, scope: BookTextScope): Flow<LibraryTextSearchResult> = flow {
        throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
    }
    /** Caller-owned job. Cancellation retains completed books and the previous unfinished generation. */
    suspend fun rebuild(onProgress: (LibraryTextIndexProgress) -> Unit)
    /** Explicit original-book job; never falls back to rebuilding or pruning the whole library.
     * A returned cache state is not source evidence or permission to transmit anything.
     */
    suspend fun rebuildBook(scope: BookTextScope, onProgress: (LibraryTextIndexProgress) -> Unit): LibraryTextBookState =
        throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
    /** Rechecks the exact stored hit and the current book before the user may open its chapter. */
    suspend fun resolve(hit: LibraryTextHit): LibraryTextHit?
}
enum class LibraryTextIndexFailure { INVALID_QUERY, SOURCE, RECOVERY, BUSY }
class LibraryTextIndexException(val reason: LibraryTextIndexFailure) : Exception(reason.name)

private val TEXT_HASH = Regex("[0-9a-f]{64}")

/** Exact addresses shared by the strict TXT decoder and its private viewing publication. */
fun txtLibraryTextPartIndex(href: String): Int? {
    val match = Regex("Text/part([0-9]{4})\\.xhtml").matchEntire(href) ?: return null
    return match.groupValues[1].toInt().takeIf { it in 1..64 }?.minus(1)
}

fun validFb2LibraryTextHref(href: String): Boolean {
    val match = Regex("Text/b([1-9][0-9]{0,3})-p([1-9][0-9]{0,3})\\.xhtml").matchEntire(href) ?: return false
    return match.groupValues.drop(1).all { it.toIntOrNull() in 1..2048 }
}

fun safeLibraryTextHref(value: String): Boolean {
    if (value.isBlank() || value.length > 2048 || value.any { it.isISOControl() || it == '\\' }) return false
    return try {
        val uri = URI(value)
        val path = uri.path ?: return false
        !uri.isAbsolute && uri.rawAuthority == null && uri.rawQuery == null && uri.rawFragment == null &&
            path.isNotBlank() && !path.startsWith('/') && !path.startsWith("//") &&
            path.none { it.isISOControl() || it == '\\' } &&
            path.split('/').none { it == "." || it == ".." } && uri.normalize().toString() == value
    } catch (_: Exception) { false }
}

/** Folding is for searching only. Original text and chapter addresses are never normalized. */
fun libraryTextSearchKey(value: String): String = value.replace('İ', 'i').replace('ı', 'i').lowercase(Locale.ROOT)

fun limitLibraryTextQuery(value: String): String {
    var end = minOf(value.length, MAX_LIBRARY_TEXT_QUERY)
    if (end < value.length && end > 0 && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
    return value.substring(0, end)
}

/** Logical persisted UTF-8 string payload; this does not claim to bound SQLite/FTS/WAL file size. */
fun indexedChunkStringBytes(bookId: String, contentSha256: String, chunk: ExtractedTextChunk): Long =
    listOf(bookId, contentSha256, chunk.resourceHref, chunk.resourceSha256, chunk.chapterTitle.orEmpty(),
        chunk.text, libraryTextSearchKey(chunk.text)).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } + 36L
