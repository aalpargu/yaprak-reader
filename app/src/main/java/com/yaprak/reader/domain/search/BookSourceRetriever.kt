package com.yaprak.reader.domain.search

import java.util.Collections
import com.yaprak.reader.domain.library.BookFormat

/** Format admission only; current extractor capability and original-source proof are still required. */
fun supportsBookSourceRetrieval(format: BookFormat): Boolean =
    format == BookFormat.EPUB || format == BookFormat.PDF || format == BookFormat.TXT || format == BookFormat.FB2 ||
        format == BookFormat.DOCX

/** Explicit whole-book scope. A reading percentage, passage or saved locator cannot create it. */
data class BookTextScope(val bookId: String, val contentSha256: String) {
    val isValid: Boolean get() = bookId.isNotBlank() && bookId.length <= 200 &&
        bookId.none { it.isISOControl() || it.isSurrogate() } && contentSha256.matches(Regex("[0-9a-f]{64}"))
}

const val MAX_BOOK_SOURCE_REFERENCES = 8
/** Separate complete-resource allowance; keyword and selected-passage retrieval remain bounded by eight. */
const val MAX_COMPLETE_BOOK_SOURCE_REFERENCES = 64

data class BookSourceReference(val label: String, val hit: LibraryTextHit, val excerpt: BookSourceExcerpt? = null) {
    val text: String get() = excerpt?.let { if (it.isValid(hit.text)) hit.text.substring(it.startUtf16, it.endUtf16) else "" }
        ?: hit.text
}

/** Immutable, bounded local evidence snapshot. This grants no network or spoiler permission.
 * Labels address only these original text chunks; they are not page numbers or DOM locators.
 * A future answer must resolve its citations against its own request's snapshot.
 */
class PreparedBookSources(
    val requestId: String,
    val scope: BookTextScope,
    val query: String,
    val totalMatches: Int,
    references: List<BookSourceReference>,
    val section: BookSourceSection? = null,
    val chapter: BookSourceChapter? = null,
    val prefix: BookSourcePrefix? = null,
    val prefixQuery: BookSourcePrefixQuery? = null,
) {
    val references: List<BookSourceReference> = Collections.unmodifiableList(ArrayList(references))
    val truncated: Boolean get() = totalMatches > references.size
}

fun interface BookSourceRetriever {
    /** Caller-owned, explicit local keyword search. Rechecks actual imported/source bytes and
     * extraction against the chosen cache chunks; does not rebuild, persist or transmit anything.
     */
    suspend fun prepare(scope: BookTextScope, query: String): PreparedBookSources

    /** Local catalog of nonempty EPUB resources/PDF text pages/TXT or FB2 parts/DOCX document text; never inferred chapters. */
    suspend fun listSections(scope: BookTextScope): List<BookSourceSection> =
        throw BookSourceException(BookSourceFailure.UNSUPPORTED)

    /** Complete selected resource or an explicit failure; never silently truncates to a request limit. */
    suspend fun prepareSection(scope: BookTextScope, section: BookSourceSection): PreparedBookSources =
        throw BookSourceException(BookSourceFailure.UNSUPPORTED)

    suspend fun listChapters(scope: BookTextScope): List<BookSourceChapter> =
        throw BookSourceException(BookSourceFailure.UNSUPPORTED)
    suspend fun prepareChapter(scope: BookTextScope, chapter: BookSourceChapter): PreparedBookSources =
        throw BookSourceException(BookSourceFailure.UNSUPPORTED)

    /** Complete extracted text from the beginning through this explicitly chosen actual TOC entry.
     * This is not inferred reading progress or a narrative/spoiler guarantee.
     */
    suspend fun preparePrefix(scope: BookTextScope, throughChapter: BookSourceChapter): PreparedBookSources =
        throw BookSourceException(BookSourceFailure.UNSUPPORTED)

    /** Explicit keyword windows within the actual beginning/TOC boundary; never global fallback. */
    suspend fun preparePrefixQuery(scope: BookTextScope, throughChapter: BookSourceChapter,
        query: String): PreparedBookSources = throw BookSourceException(BookSourceFailure.UNSUPPORTED)
}

enum class BookSourceFailure { INVALID_QUERY, NOT_INDEXED, UNSUPPORTED, LIMIT, SOURCE, RECOVERY, BUSY }
class BookSourceException(val reason: BookSourceFailure) : Exception(reason.name)
