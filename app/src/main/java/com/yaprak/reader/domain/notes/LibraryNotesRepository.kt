package com.yaprak.reader.domain.notes

import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.reader.EpubSourceAnchor
import kotlinx.coroutines.flow.Flow

/** A saved note together with its current, content-matched library book. */
enum class LibraryNoteSource { BOOK_NOTE, EPUB_PASSAGE, PDF_ANNOTATION }
data class LibraryNoteIdentity(val source: LibraryNoteSource, val id: String)

data class LibraryNote(
    val id: String,
    val bookId: String,
    val contentSha256: String,
    val bookTitle: String,
    val bookAuthors: String?,
    val bookFormat: BookFormat,
    val text: String,
    val createdAt: Long,
    val pdfPageIndex: Int? = null,
    val source: LibraryNoteSource = LibraryNoteSource.BOOK_NOTE,
    val sourceText: String? = null,
    val sourceAnchor: EpubSourceAnchor? = null,
    val sourceRevision: Long = 0,
    val highlightColor: EpubHighlightColor? = null,
    val pdfSourceAnchor: com.yaprak.reader.reader.PdfSourceAnchor? = null,
    val pdfHighlightColor: com.yaprak.reader.domain.pdfannotations.PdfHighlightColor? = null,
) {
    val identity: LibraryNoteIdentity get() = LibraryNoteIdentity(source, id)
}

data class LibraryNotesResult(val entries: List<LibraryNote>, val totalCount: Int)

const val MAX_LIBRARY_NOTE_RESULTS = 200
const val MAX_LIBRARY_NOTE_QUERY_LENGTH = 200

/** An unavailable or invalid local source must not look like an empty note list. */
class LibraryNotesSourceException(cause: Throwable? = null) :
    Exception("Library notes source is unavailable or invalid", cause)

interface LibraryNotesRepository {
    fun observe(query: String): Flow<LibraryNotesResult>
    suspend fun find(bookId: String, contentSha256: String, noteId: String): LibraryNote?
    suspend fun find(bookId: String, contentSha256: String, noteId: String, source: LibraryNoteSource): LibraryNote? =
        if (source == LibraryNoteSource.BOOK_NOTE) find(bookId, contentSha256, noteId)
        else throw LibraryNotesSourceException()
}

/** Bound UTF-16 input without cutting a supplementary character in half. */
fun limitLibraryNoteQuery(query: String): String {
    if (query.length <= MAX_LIBRARY_NOTE_QUERY_LENGTH) return query
    val limit = MAX_LIBRARY_NOTE_QUERY_LENGTH
    val safeLimit = if (Character.isHighSurrogate(query[limit - 1]) &&
        Character.isLowSurrogate(query[limit])) limit - 1 else limit
    return query.take(safeLimit)
}
