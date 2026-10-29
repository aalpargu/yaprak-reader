package com.yaprak.reader.data.exporting

import android.database.Cursor
import androidx.room.Transactor.SQLiteTransactionType
import androidx.room.useReaderConnection
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.EpubPassageSql
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.notes.MAX_BOOK_NOTE_LENGTH
import com.yaprak.reader.domain.vocabulary.MAX_ENGLISH_WORD_LENGTH
import com.yaprak.reader.domain.vocabulary.MAX_TURKISH_MEANING_LENGTH
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reads one content-matched snapshot. The exporter owns the shared file mutex around this call. */
class RoomBookStudySource(
    private val database: LibraryDatabase,
    private val validateRead: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BookStudySource {
    override suspend fun snapshot(bookId: String, contentSha256: String): BookStudySnapshot =
        snapshot(bookId, contentSha256, StudyExportFormat.TEXT)

    override suspend fun snapshot(bookId: String, contentSha256: String, format: StudyExportFormat): BookStudySnapshot {
        if (format == StudyExportFormat.PDF || format == StudyExportFormat.ANKI_STUDY_TSV || format == StudyExportFormat.NOTION_CSV || format == StudyExportFormat.OBSIDIAN_ZIP)
            throw StudyExportException(StudyExportFailure.UNSUPPORTED_FORMAT)
        return read { sql, caller ->
            val book = readBook(sql, caller, bookId, contentSha256)
            val text = studyExportTextWriter(format, book.title, book.authors, book.format.name)
            var notes = 0
            if (format != StudyExportFormat.ANKI_TSV) readNotes(sql, caller, bookId, contentSha256, book) { note ->
                validStudyRecord(notes < Int.MAX_VALUE)
                text.note(++notes, note.text, note.pdfPageIndex?.toLong())
            }
            text.endNotes(notes)
            var words = 0
            readWords(sql, caller, bookId, contentSha256) { word ->
                validStudyRecord(words < Int.MAX_VALUE)
                text.word(++words, word.english, word.turkish)
            }
            text.endWords(words)
            var passages = 0
            var attachedNotes = 0
            if (format != StudyExportFormat.ANKI_TSV && book.format == BookFormat.EPUB)
                readPassages(sql, caller, bookId, contentSha256, strictAnchors = false) { passage ->
                    if (passage.attachedNote != null) {
                        validStudyRecord(attachedNotes < Int.MAX_VALUE - notes)
                        attachedNotes++
                    }
                    validStudyRecord(passages < Int.MAX_VALUE)
                    text.passage(++passages, passage.text, passage.anchor.resourceHref,
                        passage.highlightColor, passage.attachedNote)
                }
            text.endPassages(passages)
            if (format != StudyExportFormat.ANKI_TSV) readPdfAnnotations(sql, caller, bookId, contentSha256) { annotation ->
                validStudyRecord(passages < Int.MAX_VALUE)
                text.pdfAnnotation(++passages, annotation.text, annotation.anchor.pageIndex,
                    annotation.highlightColor, annotation.attachedNote)
                if (annotation.attachedNote != null) {
                    validStudyRecord(attachedNotes < Int.MAX_VALUE - notes)
                    attachedNotes++
                }
            }
            if (notes == 0 && words == 0 && passages == 0) throw StudyExportException(StudyExportFailure.NO_RECORDS)
            ensureReadActive(caller)
            BookStudySnapshot(text.bytes(), notes + attachedNotes, words, passages)
        }
    }

    override suspend fun snapshotRecords(bookId: String, contentSha256: String): StudyRecordsSnapshot = read { sql, caller ->
        val book = readBook(sql, caller, bookId, contentSha256, strictHash = true)
        val budget = StudyRecordsBudget(book)
        val notes = ArrayList<StudyNoteRecord>()
        val words = ArrayList<StudyWordRecord>()
        val passages = ArrayList<StudyPassageRecord>()
        val pdfAnnotations = ArrayList<StudyPdfAnnotationRecord>()
        readNotes(sql, caller, bookId, contentSha256, book) { budget.note(it); notes.add(it) }
        readWords(sql, caller, bookId, contentSha256) { budget.word(it); words.add(it) }
        // A typed PDF snapshot checks every current source row, including unknown anchor versions.
        readPassages(sql, caller, bookId, contentSha256, strictAnchors = true) { budget.passage(it); passages.add(it) }
        readPdfAnnotations(sql, caller, bookId, contentSha256) { budget.pdfAnnotation(it); pdfAnnotations.add(it) }
        budget.finish()
        ensureReadActive(caller)
        StudyRecordsSnapshot(book, notes, words, passages, pdfAnnotations)
    }

    private suspend fun <T> read(block: suspend (SupportSQLiteDatabase, CoroutineContext) -> T): T = withContext(io) {
        val caller = currentCoroutineContext()
        try {
            caller.ensureActive()
            // Recovery holds the same exporter mutex. This callback must check, never await or reacquire it.
            validateRead()
            try {
                database.useReaderConnection { connection ->
                    connection.withTransaction(SQLiteTransactionType.DEFERRED) {
                        ensureReadActive(caller)
                        EpubPassageSql.checkDistinctIds(database.openHelper.writableDatabase)
                        val result = block(database.openHelper.writableDatabase, caller)
                        ensureReadActive(caller)
                        result
                    }
                }
            } catch (cancelled: CancellationException) {
                caller.ensureActive()
                throw StudyExportException(StudyExportFailure.IO)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: StudyExportException) {
            throw problem
        } catch (_: Exception) {
            throw StudyExportException(StudyExportFailure.IO)
        }
    }

    private suspend fun readBook(sql: SupportSQLiteDatabase, caller: CoroutineContext,
        bookId: String, hash: String, strictHash: Boolean = false): StudyExportBook = sql.query(SimpleSQLiteQuery(
        "SELECT title, authors, format, sha256, pageCount FROM books WHERE id = ?", arrayOf(bookId),
    )).use { cursor ->
        ensureReadActive(caller)
        if (!cursor.moveToFirst()) throw StudyExportException(StudyExportFailure.MISSING_BOOK)
        ensureReadActive(caller)
        val savedHash = cursor.text(3, 64)
        if (strictHash) validStudyRecord(STUDY_SOURCE_HASH.matches(savedHash))
        if (savedHash != hash) throw StudyExportException(StudyExportFailure.CHANGED_BOOK)
        val title = cursor.text(0, 1000)
        val authors = if (cursor.isNull(1)) null else cursor.text(1, 2000)
        val format = when (cursor.text(2, 4)) {
            "EPUB" -> BookFormat.EPUB
            "PDF" -> BookFormat.PDF
            "TXT" -> BookFormat.TXT
            "CBZ" -> BookFormat.CBZ
            "CBR" -> BookFormat.CBR
            "FB2" -> BookFormat.FB2
            "DOCX" -> BookFormat.DOCX
            else -> throw StudyExportException(StudyExportFailure.IO)
        }
        val pageCount = if (cursor.isNull(4)) null else cursor.integer(4).also {
            validStudyRecord(it in 1..Int.MAX_VALUE.toLong())
        }.toInt()
        validStudyRecord(format != BookFormat.PDF || pageCount != null)
        validStudyRecord(!format.comic || pageCount != null && pageCount in 1..2000)
        StudyExportBook(title, authors, format, pageCount)
    }

    private suspend fun readNotes(sql: SupportSQLiteDatabase, caller: CoroutineContext, bookId: String,
        hash: String, book: StudyExportBook, consume: (StudyNoteRecord) -> Unit) = rows(sql, caller,
        "SELECT text, pdfPageIndex FROM book_notes WHERE bookId = ? AND contentSha256 = ? " +
            "ORDER BY createdAt DESC, id DESC", arrayOf(bookId, hash)) { cursor ->
        val text = cursor.text(0, MAX_BOOK_NOTE_LENGTH)
        val page = if (cursor.isNull(1)) null else cursor.integer(1).also {
            validStudyRecord(book.format == BookFormat.PDF && book.pageCount != null && it >= 0 && it < book.pageCount)
        }.toInt()
        consume(StudyNoteRecord(text, page))
    }

    private suspend fun readWords(sql: SupportSQLiteDatabase, caller: CoroutineContext, bookId: String,
        hash: String, consume: (StudyWordRecord) -> Unit) = rows(sql, caller,
        "SELECT english, turkish FROM saved_words WHERE bookId = ? AND contentSha256 = ? " +
            "ORDER BY createdAt DESC, id DESC", arrayOf(bookId, hash)) { cursor ->
        consume(StudyWordRecord(cursor.text(0, MAX_ENGLISH_WORD_LENGTH, singleLine = true),
            cursor.text(1, MAX_TURKISH_MEANING_LENGTH, singleLine = true)))
    }

    private suspend fun readPassages(sql: SupportSQLiteDatabase, caller: CoroutineContext, bookId: String,
        hash: String, strictAnchors: Boolean, consume: (StudyPassageRecord) -> Unit) {
        val lease = currentCoroutineContext()
        EpubPassageSql.scanCurrent(sql, bookId, hash, { lease.ensureActive(); caller.ensureActive() },
            includeUnknownLegacy = strictAnchors, requireEpubBook = false) { passage ->
            consume(StudyPassageRecord(passage.text, passage.anchor, passage.highlightColor,
                passage.attachedNote, passage.revision))
        }
    }

    private suspend fun readPdfAnnotations(sql: SupportSQLiteDatabase, caller: CoroutineContext,
        bookId: String, hash: String, consume: (StudyPdfAnnotationRecord) -> Unit) {
        val lease = currentCoroutineContext()
        com.yaprak.reader.data.persistence.PdfAnnotationSql.scan(sql, bookId, hash,
            { lease.ensureActive(); caller.ensureActive() }) { annotation ->
            consume(StudyPdfAnnotationRecord(annotation.text, annotation.anchor, annotation.highlightColor,
                annotation.attachedNote, annotation.revision))
        }
    }

    private suspend fun rows(sql: SupportSQLiteDatabase, caller: CoroutineContext, query: String,
        args: Array<String>, consume: (Cursor) -> Unit) =
        scanStudyExportCursor(sql.query(SimpleSQLiteQuery(query, args)), caller, consume)

    private fun Cursor.text(index: Int, limit: Int, singleLine: Boolean = false): String {
        validStudyRecord(getType(index) == Cursor.FIELD_TYPE_STRING)
        val value = getString(index)
        validStudyRecord(value.length in 1..limit && value.isNotBlank())
        if (singleLine) validStudyRecord(value.none { it == '\r' || it == '\n' })
        return value
    }

    private fun Cursor.integer(index: Int): Long {
        validStudyRecord(getType(index) == Cursor.FIELD_TYPE_INTEGER)
        return getLong(index)
    }

    private suspend fun ensureReadActive(caller: CoroutineContext) {
        currentCoroutineContext().ensureActive()
        caller.ensureActive()
    }
}

/** Own and close the cursor even when Room's job differs from the original export caller. */
internal suspend fun scanStudyExportCursor(cursor: Cursor, caller: CoroutineContext, consume: (Cursor) -> Unit) = cursor.use {
    suspend fun active() { currentCoroutineContext().ensureActive(); caller.ensureActive() }
    while (true) {
        active()
        if (!cursor.moveToNext()) break
        active()
        consume(cursor)
    }
    active()
}
