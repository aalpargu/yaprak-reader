package com.yaprak.reader.data.persistence

import android.database.Cursor
import androidx.room.Transactor.SQLiteTransactionType
import androidx.room.useReaderConnection
import androidx.sqlite.db.SimpleSQLiteQuery
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.notes.LibraryNote
import com.yaprak.reader.domain.notes.LibraryNoteSource
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.passages.EpubRangeAnchorCodec
import com.yaprak.reader.domain.passages.MAX_EPUB_PASSAGE_LENGTH
import com.yaprak.reader.domain.passages.MAX_EPUB_ATTACHED_NOTE_LENGTH
import com.yaprak.reader.domain.passages.isWellFormedEpubAnnotationText
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.domain.notes.LibraryNotesRepository
import com.yaprak.reader.domain.notes.LibraryNotesResult
import com.yaprak.reader.domain.notes.LibraryNotesSourceException
import com.yaprak.reader.domain.notes.MAX_BOOK_NOTE_LENGTH
import com.yaprak.reader.domain.notes.MAX_LIBRARY_NOTE_RESULTS
import com.yaprak.reader.domain.notes.limitLibraryNoteQuery
import com.yaprak.reader.domain.notes.LibraryNotesExportSource
import com.yaprak.reader.domain.notes.LibraryNotesExportSnapshot
import com.yaprak.reader.domain.notes.LibraryNoteExportEntry
import com.yaprak.reader.domain.notes.LibraryNotesExportException
import com.yaprak.reader.data.exporting.LibraryNotesExportBudget
import com.yaprak.reader.data.exporting.StudyExportException
import java.util.Locale
import java.net.URI
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Scans a content-matched join, retaining only the newest bounded result set. */
class RoomLibraryNotesRepository(
    private val database: LibraryDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fileMutex: Mutex = Mutex(),
    private val validateRead: () -> Unit = {},
) : LibraryNotesRepository, LibraryNotesExportSource {
    override fun observe(query: String) =
        database.invalidationTracker.createFlow("books", "book_notes", "epub_passages", "epub_range_passages", "pdf_annotations").catch { failure ->
            // Only classify tracker failures here. A guard-thrown cancellation in the downstream read stays cancelled.
            when (failure) {
                is CancellationException -> {
                    currentCoroutineContext().ensureActive()
                    throw LibraryNotesSourceException(failure)
                }
                is LibraryNotesSourceException -> throw failure
                is Exception -> throw LibraryNotesSourceException(failure)
                else -> throw failure
            }
        }.map {
            read { caller ->
                readLibraryNotesCursor(database.openHelper.writableDatabase.query(
                    SimpleSQLiteQuery(JOINED_NOTES),
                ), limitLibraryNoteQuery(query), caller)
            }
        }

    override suspend fun find(bookId: String, contentSha256: String, noteId: String): LibraryNote? =
        find(bookId, contentSha256, noteId, LibraryNoteSource.BOOK_NOTE)

    /** Export scans the whole query in one current read, independently of the UI's newest200 cap. */
    override suspend fun snapshot(query: String): LibraryNotesExportSnapshot = read { caller ->
        val budget = LibraryNotesExportBudget(query)
        val terms = libraryNoteTerms(query)
        val entries = ArrayList<LibraryNoteExportEntry>()
        database.openHelper.writableDatabase.query(SimpleSQLiteQuery(JOINED_NOTES)).use { cursor ->
            while (true) {
                ensureReadActive(caller)
                if (!cursor.moveToNext()) break
                ensureReadActive(caller)
                val note = cursor.libraryNote()
                if (!note.matchesTerms(terms)) continue
                val pageCount = if (cursor.isNull(9)) null else cursor.getLong(9).toInt()
                val entry = LibraryNoteExportEntry(note, pageCount)
                budget.add(entry)
                entries.add(entry)
            }
        }
        ensureReadActive(caller)
        LibraryNotesExportSnapshot(query, entries.sortedWith { first, second ->
            oldestNoteFirst.reversed().compare(first.note, second.note)
        })
    }

    override suspend fun find(bookId: String, contentSha256: String, noteId: String, source: LibraryNoteSource): LibraryNote? = read { caller ->
        database.openHelper.writableDatabase.query(SimpleSQLiteQuery(
            "$JOINED_NOTES WHERE bookId = ? AND contentSha256 = ? AND id = ? AND source = ?",
            arrayOf(bookId, contentSha256, noteId, source.name),
        )).use { cursor ->
            ensureReadActive(caller)
            if (!cursor.moveToFirst()) null else {
                ensureReadActive(caller)
                cursor.libraryNote()
            }
        }
    }

    private suspend fun <T> read(block: suspend (CoroutineContext) -> T): T = withContext(io) {
        val caller = currentCoroutineContext()
        try {
            fileMutex.withLock {
                caller.ensureActive()
                // Recheck after waiting behind restore/import/removal; never await startup under this lock.
                validateRead()
                try {
                    // This public lease respects Room's lifetime; legacy withTransaction can reopen a closed helper.
                    database.useReaderConnection { connection ->
                        connection.withTransaction(SQLiteTransactionType.DEFERRED) {
                            ensureReadActive(caller)
                            EpubPassageSql.checkDistinctIds(database.openHelper.writableDatabase)
                            block(caller)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    // Room owns a separate job. A closed Room source is a failure while a cancelled caller stays cancelled.
                    caller.ensureActive()
                    throw LibraryNotesSourceException(cancelled)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LibraryNotesExportException) {
            throw failure
        } catch (failure: StudyExportException) {
            throw failure
        } catch (invalid: LibraryNotesSourceException) {
            throw invalid
        } catch (failure: Exception) {
            throw LibraryNotesSourceException(failure)
        }
    }
}

private const val JOINED_NOTES = """
    SELECT * FROM (
    SELECT note.id, note.bookId, note.contentSha256, book.title, book.authors,
           book.format, note.text, note.createdAt, note.pdfPageIndex, book.pageCount,
           'BOOK_NOTE' AS source, NULL AS sourceText, NULL AS resourceHref,
           NULL AS nodeTextSha256, NULL AS startOffset, NULL AS endOffset,
           0 AS revision, NULL AS highlightColor, NULL AS anchorVersion,
           NULL AS anchorJson, NULL AS anchorKey, NULL AS selectionKey
    FROM book_notes AS note INNER JOIN books AS book
        ON book.id = note.bookId AND book.sha256 = note.contentSha256
    UNION ALL
    SELECT passage.id, passage.bookId, passage.contentSha256, book.title, book.authors,
           book.format, passage.attachedNote, passage.createdAt, NULL, book.pageCount,
           'EPUB_PASSAGE', passage.text, passage.resourceHref, passage.nodeTextSha256,
           passage.startOffset, passage.endOffset, passage.revision, passage.highlightColor,
           passage.anchorVersion, NULL, NULL, NULL
    FROM epub_passages AS passage INNER JOIN books AS book
        ON book.id = passage.bookId AND book.sha256 = passage.contentSha256
    WHERE passage.attachedNote IS NOT NULL
    UNION ALL
    SELECT passage.id, passage.bookId, passage.contentSha256, book.title, book.authors,
           book.format, passage.attachedNote, passage.createdAt, NULL, book.pageCount,
           'EPUB_PASSAGE', passage.text, passage.resourceHref, NULL, NULL, NULL,
           passage.revision, passage.highlightColor, passage.anchorVersion, passage.anchorJson, passage.anchorKey, NULL
    FROM epub_range_passages AS passage INNER JOIN books AS book
        ON book.id = passage.bookId AND book.sha256 = passage.contentSha256
    WHERE passage.attachedNote IS NOT NULL
    UNION ALL
    SELECT annotation.id, annotation.bookId, annotation.contentSha256, book.title, book.authors,
           book.format, annotation.attachedNote, annotation.createdAt, annotation.pageIndex, book.pageCount,
           'PDF_ANNOTATION', annotation.text, NULL, NULL, NULL, NULL,
           annotation.revision, annotation.highlightColor, annotation.anchorVersion, annotation.anchorJson,
           annotation.anchorKey, annotation.selectionKey
    FROM pdf_annotations AS annotation INNER JOIN books AS book
        ON book.id = annotation.bookId AND book.sha256 = annotation.contentSha256
    WHERE annotation.attachedNote IS NOT NULL
    )
"""

private val oldestNoteFirst = compareBy<LibraryNote> { it.createdAt }.thenBy { it.id }.thenBy { it.source.name }
private val contentHash = Regex("[0-9a-f]{64}")

/** Owns and closes the joined cursor, including on source failure or coroutine cancellation. */
internal suspend fun readLibraryNotesCursor(
    cursor: Cursor,
    query: String,
    cancellationContext: CoroutineContext = EmptyCoroutineContext,
): LibraryNotesResult = cursor.use {
    val terms = libraryNoteTerms(query)
    val newest = PriorityQueue(MAX_LIBRARY_NOTE_RESULTS, oldestNoteFirst)
    var count = 0
    while (true) {
        ensureReadActive(cancellationContext)
        if (!cursor.moveToNext()) break
        ensureReadActive(cancellationContext)
        val note = cursor.libraryNote()
        if (!note.matchesTerms(terms)) continue
        valid(count < Int.MAX_VALUE)
        count++
        if (newest.size < MAX_LIBRARY_NOTE_RESULTS) newest.add(note)
        else if (oldestNoteFirst.compare(note, newest.peek()) > 0) {
            newest.poll()
            newest.add(note)
        }
    }
    ensureReadActive(cancellationContext)
    LibraryNotesResult(newest.toList().sortedWith(oldestNoteFirst.reversed()), count)
}

private fun String.searchKey() = lowercase(Locale.ROOT).replace("i\u0307", "i").replace('ı', 'i')

private fun libraryNoteTerms(query: String) = limitLibraryNoteQuery(query).searchKey()
    .split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)

private fun LibraryNote.matchesTerms(terms: List<String>): Boolean {
    if (terms.isEmpty()) return true
    val corpus = (text + "\n" + (sourceText ?: "") + "\n" + bookTitle + "\n" + (bookAuthors ?: "")).searchKey()
    return terms.all(corpus::contains)
}

private suspend fun ensureReadActive(caller: CoroutineContext) {
    currentCoroutineContext().ensureActive()
    // useReaderConnection runs under Room's job; still honor the collector/find caller at every row boundary.
    caller.ensureActive()
}

private fun Cursor.libraryNote(): LibraryNote {
    valid(columnCount == 22)
    val id = text(0, 1000)
    val bookId = text(1, 1000)
    val hash = text(2, 64).also { valid(contentHash.matches(it)) }
    val title = text(3, 1000)
    val authors = if (isNull(4)) null else text(4, 2000)
    val format = when (text(5, 4)) {
        "EPUB" -> BookFormat.EPUB
        "PDF" -> BookFormat.PDF
        "TXT" -> BookFormat.TXT
        "CBZ" -> BookFormat.CBZ
            "CBR" -> BookFormat.CBR
        "FB2" -> BookFormat.FB2
            "DOCX" -> BookFormat.DOCX
        else -> throw LibraryNotesSourceException()
    }
    val noteText = text(6, MAX_BOOK_NOTE_LENGTH)
    valid(!isNull(7) && getType(7) == Cursor.FIELD_TYPE_INTEGER)
    val createdAt = getLong(7)
    val pageCount = if (isNull(9)) null else {
        valid(getType(9) == Cursor.FIELD_TYPE_INTEGER)
        getLong(9).also { valid(it in 1..Int.MAX_VALUE.toLong()) }
    }
    valid(format != BookFormat.PDF || pageCount != null)
    valid(!format.comic || pageCount != null && pageCount in 1..2000)
    val page = if (isNull(8)) null else {
        valid(getType(8) == Cursor.FIELD_TYPE_INTEGER)
        getLong(8).also {
            valid(format == BookFormat.PDF && pageCount != null && it >= 0 && it < pageCount)
        }.toInt()
    }
    val source = when (text(10, 14)) {
        "BOOK_NOTE" -> LibraryNoteSource.BOOK_NOTE
        "EPUB_PASSAGE" -> LibraryNoteSource.EPUB_PASSAGE
        "PDF_ANNOTATION" -> LibraryNoteSource.PDF_ANNOTATION
        else -> throw LibraryNotesSourceException()
    }
    valid(getType(16) == Cursor.FIELD_TYPE_INTEGER)
    val revision = getLong(16).also { valid(it >= 0) }
    if (source == LibraryNoteSource.BOOK_NOTE) {
        valid((11..15).all { isNull(it) } && revision == 0L && (17..21).all { isNull(it) })
        return LibraryNote(id, bookId, hash, title, authors, format, noteText, createdAt, page)
    }
    if (source == LibraryNoteSource.PDF_ANNOTATION) {
        valid(format == BookFormat.PDF && page != null && (12..15).all { isNull(it) })
        val annotation = try { PdfAnnotationSql.read(this, "sourceText", "text", "pdfPageIndex") }
            catch (failure: Exception) { throw LibraryNotesSourceException(failure) }
        return LibraryNote(id, bookId, hash, title, authors, format, noteText, createdAt, page,
            source, annotation.text, sourceRevision = annotation.revision,
            pdfSourceAnchor = annotation.anchor, pdfHighlightColor = annotation.highlightColor)
    }
    valid(isNull(21))
    valid(format == BookFormat.EPUB && page == null && getType(18) == Cursor.FIELD_TYPE_INTEGER)
    val version = getLong(18)
    valid(version == 1L || version == 2L)
    val quote = text(11, MAX_EPUB_PASSAGE_LENGTH)
    valid(noteText.length <= MAX_EPUB_ATTACHED_NOTE_LENGTH)
    valid(isWellFormedEpubAnnotationText(quote) && isWellFormedEpubAnnotationText(noteText))
    val href = text(12, 4096).also { valid(safePassageHref(it)) }
    val anchor = if (version == 1L) {
        valid(isNull(19) && isNull(20))
        val nodeHash = text(13, 64).also { valid(contentHash.matches(it)) }
        valid(getType(14) == Cursor.FIELD_TYPE_INTEGER && getType(15) == Cursor.FIELD_TYPE_INTEGER)
        val start = getLong(14); val end = getLong(15)
        valid(start >= 0 && end > start && end <= MAX_EPUB_PASSAGE_LENGTH && end - start == quote.length.toLong())
        SourceTextAnchor(href, start.toInt(), end.toInt(), nodeHash)
    } else {
        valid((13..15).all { isNull(it) })
        try { EpubRangeAnchorCodec.verify(href, text(19, EpubRangeAnchorCodec.MAX_JSON_UNITS), text(20, 64)) }
        catch (invalid: Exception) { throw LibraryNotesSourceException(invalid) }
    }
    val color = if (isNull(17)) null else {
        EpubHighlightColor.entries.singleOrNull { it.name == text(17, 20) } ?: throw LibraryNotesSourceException()
    }
    return LibraryNote(id, bookId, hash, title, authors, format, noteText, createdAt, null,
        source, quote, anchor, revision, color)
}

private fun safePassageHref(href: String): Boolean = try {
    val uri = URI(href); val path = uri.path
    !uri.isAbsolute && !uri.isOpaque && uri.rawAuthority == null && uri.rawQuery == null && uri.rawFragment == null &&
        !href.contains('\\') && !Regex("%(?:2[fF]|5[cC])").containsMatchIn(href) && href.none(Char::isISOControl) &&
        path != null && path.isNotEmpty() && !path.startsWith('/') && !path.contains('\\') &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) } && uri.rawPath == href
} catch (_: IllegalArgumentException) { false }

private fun Cursor.text(index: Int, limit: Int): String {
    valid(!isNull(index) && getType(index) == Cursor.FIELD_TYPE_STRING)
    return getString(index).also { valid(it.length in 1..limit && it.isNotBlank()) }
}

private fun valid(condition: Boolean) {
    if (!condition) throw LibraryNotesSourceException()
}
