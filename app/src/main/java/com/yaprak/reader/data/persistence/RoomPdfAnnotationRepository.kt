package com.yaprak.reader.data.persistence

import android.content.ContentValues
import androidx.room.Transactor.SQLiteTransactionType
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.reader.PdfSourceAnchor
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fresh native proof precedes each write; metadata, exact expected rows and caller are checked again at commit. */
class RoomPdfAnnotationRepository(private val database: LibraryDatabase,
    private val original: PdfAnnotationOriginalSource, private val fileMutex: Mutex,
    private val validateAccess: () -> Unit = {}, private val io: CoroutineDispatcher = Dispatchers.IO) : PdfAnnotationRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.invalidationTracker.createFlow("books", PdfAnnotationSql.TABLE).catch { failure ->
            currentCoroutineContext().ensureActive(); throw PdfAnnotationSourceException(failure)
        }.map {
            locked {
                currentBook(bookId, contentSha256)
                read { sql, checkActive -> PdfAnnotationSql.current(sql, bookId, contentSha256, checkActive) }
            }
        }

    override suspend fun create(bookId: String, contentSha256: String, text: String, anchor: PdfSourceAnchor,
        highlightColor: PdfHighlightColor?, attachedNote: String?): PdfAnnotation {
        validatePdfAnnotation(text, highlightColor, attachedNote)
        val json = PdfAnchorCodec.encode(anchor); val key = PdfAnchorCodec.key(anchor); val selectionKey = PdfAnchorCodec.selectionKey(anchor, text)
        return locked {
            val book = currentBook(bookId, contentSha256); requirePage(book, anchor)
            original.verify(book.toBook(), text, anchor)
            write { sql, checkActive ->
                requireBook(sql, book)
                sql.query(SimpleSQLiteQuery("SELECT 1 FROM pdf_annotations WHERE bookId = ? AND contentSha256 = ? AND selectionKey = ?",
                    arrayOf(bookId, contentSha256, selectionKey))).use { if (it.moveToFirst()) throw PdfAnnotationChangedException() }
                sql.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM pdf_annotations WHERE bookId = ? AND contentSha256 = ?",
                    arrayOf(bookId, contentSha256))).use {
                    check(it.moveToFirst()); if (it.getLong(0) >= MAX_PDF_ANNOTATIONS_PER_BOOK) throw PdfAnnotationLimitException()
                }
                val annotation = PdfAnnotation(UUID.randomUUID().toString(), text, anchor, System.currentTimeMillis(), highlightColor, attachedNote)
                val values = ContentValues().apply {
                    put("id", annotation.id); put("bookId", bookId); put("contentSha256", contentSha256)
                    put("anchorVersion", PdfSourceAnchor.VERSION); put("pageIndex", anchor.pageIndex)
                    put("anchorJson", json); put("anchorKey", key); put("selectionKey", selectionKey); put("text", text); put("createdAt", annotation.createdAt)
                    put("highlightColor", highlightColor?.name); put("attachedNote", attachedNote); put("revision", 0L)
                }
                checkActive(); check(sql.insert(PdfAnnotationSql.TABLE, 0, values) != -1L); annotation
            }
        }
    }
    override suspend fun update(bookId: String, contentSha256: String, expected: PdfAnnotation,
        highlightColor: PdfHighlightColor?, attachedNote: String?): PdfAnnotation {
        validatePdfAnnotation(expected.text, highlightColor, attachedNote)
        check(expected.revision in 0 until Long.MAX_VALUE)
        return locked {
            val book = currentBook(bookId, contentSha256); requirePage(book, expected.anchor)
            read { sql, _ -> requireExpected(sql, bookId, contentSha256, expected) }
            original.verify(book.toBook(), expected.text, expected.anchor)
            write { sql, checkActive ->
                requireBook(sql, book); requireExpected(sql, bookId, contentSha256, expected)
                val next = expected.copy(highlightColor = highlightColor, attachedNote = attachedNote, revision = expected.revision + 1)
                val values = ContentValues().apply {
                    put("highlightColor", highlightColor?.name); put("attachedNote", attachedNote); put("revision", next.revision)
                }
                checkActive()
                if (sql.update(PdfAnnotationSql.TABLE, 0, values, "id = ? AND bookId = ? AND contentSha256 = ? AND revision = ?",
                        arrayOf(expected.id, bookId, contentSha256, expected.revision.toString())) != 1) throw PdfAnnotationChangedException()
                next
            }
        }
    }
    override suspend fun remove(bookId: String, contentSha256: String, expected: PdfAnnotation) = locked {
        val book = currentBook(bookId, contentSha256)
        // Deliberate removal can recover an annotation whose native source is no longer supported.
        write { sql, checkActive ->
            requireBook(sql, book); requireExpected(sql, bookId, contentSha256, expected); checkActive()
            if (sql.delete(PdfAnnotationSql.TABLE, "id = ? AND bookId = ? AND contentSha256 = ? AND revision = ?",
                    arrayOf(expected.id, bookId, contentSha256, expected.revision.toString())) != 1) throw PdfAnnotationChangedException()
            Unit
        }
    }
    override suspend fun verifyExisting(bookId: String, contentSha256: String, expected: List<PdfAnnotation>) = locked {
        require(expected.size <= MAX_PDF_ANNOTATIONS_PER_BOOK && expected.map { it.id }.toSet().size == expected.size)
        val book = currentBook(bookId, contentSha256)
        read { sql, _ -> expected.forEach { requireExpected(sql, bookId, contentSha256, it); requirePage(book, it.anchor) } }
        original.verifyAll(book.toBook(), expected)
        read { sql, checkActive ->
            requireBook(sql, book); expected.forEach { checkActive(); requireExpected(sql, bookId, contentSha256, it) }
        }
    }
    private suspend fun currentBook(id: String, hash: String): BookEntity {
        require(id.length in 1..1000 && id.isNotBlank() && Regex("[0-9a-f]{64}").matches(hash))
        val book = database.books().findById(id) ?: throw PdfAnnotationSourceException()
        if (book.sha256 != hash || book.format != "PDF" || book.pageCount == null || book.pageCount <= 0)
            throw PdfAnnotationSourceException()
        read { sql, _ -> requireBook(sql, book) }
        return book
    }
    private fun requirePage(book: BookEntity, anchor: PdfSourceAnchor) {
        if (anchor.pageIndex !in 0 until (book.pageCount ?: 0)) throw PdfAnnotationSourceException()
    }
    private fun requireBook(sql: SupportSQLiteDatabase, expected: BookEntity) {
        sql.query(SimpleSQLiteQuery("SELECT id,title,authors,format,originalName,storageName,byteSize,sha256,importedAt,pageCount FROM books WHERE id = ?",
            arrayOf(expected.id))).use {
            if (!it.moveToFirst() || listOf(0, 1, 3, 4, 5, 7).any { column -> it.getType(column) != android.database.Cursor.FIELD_TYPE_STRING } ||
                (!it.isNull(2) && it.getType(2) != android.database.Cursor.FIELD_TYPE_STRING) ||
                listOf(6, 8, 9).any { column -> it.getType(column) != android.database.Cursor.FIELD_TYPE_INTEGER } ||
                it.getString(0) != expected.id || it.getString(1) != expected.title ||
                it.getString(2) != expected.authors || it.getString(3) != expected.format || it.getString(4) != expected.originalName ||
                it.getString(5) != expected.storageName || it.getLong(6) != expected.byteSize || it.getString(7) != expected.sha256 ||
                it.getLong(8) != expected.importedAt || it.isNull(9) || it.getLong(9) != expected.pageCount?.toLong())
                throw PdfAnnotationSourceException()
        }
    }
    private fun requireExpected(sql: SupportSQLiteDatabase, bookId: String, hash: String, expected: PdfAnnotation) {
        sql.query(SimpleSQLiteQuery("SELECT * FROM pdf_annotations WHERE id = ? AND bookId = ? AND contentSha256 = ?",
            arrayOf(expected.id, bookId, hash))).use {
            if (!it.moveToFirst() || PdfAnnotationSql.read(it) != expected) throw PdfAnnotationChangedException()
        }
    }
    private suspend fun <T> read(block: (SupportSQLiteDatabase, () -> Unit) -> T): T {
        val caller = currentCoroutineContext()
        return database.useReaderConnection { connection -> connection.withTransaction(SQLiteTransactionType.DEFERRED) { work(caller, block) } }
    }
    private suspend fun <T> write(block: (SupportSQLiteDatabase, () -> Unit) -> T): T {
        val caller = currentCoroutineContext()
        return database.useWriterConnection { connection -> connection.withTransaction(SQLiteTransactionType.IMMEDIATE) { work(caller, block) } }
    }
    private suspend fun <T> work(caller: kotlin.coroutines.CoroutineContext, block: (SupportSQLiteDatabase, () -> Unit) -> T): T {
        val lease = currentCoroutineContext()
        val active = { caller.ensureActive(); lease.ensureActive(); validateAccess() }
        active(); return block(database.openHelper.writableDatabase, active).also { active() }
    }
    private suspend fun <T> locked(block: suspend () -> T): T {
        val caller = currentCoroutineContext()
        return withContext(io) {
            fileMutex.withLock {
                try { caller.ensureActive(); validateAccess(); block().also { caller.ensureActive(); validateAccess() } }
                catch (cancelled: CancellationException) { caller.ensureActive(); throw PdfAnnotationSourceException(cancelled) }
                catch (known: PdfAnnotationSourceException) { throw known }
                catch (known: PdfAnnotationChangedException) { throw known }
                catch (known: PdfAnnotationLimitException) { throw known }
                catch (failure: Exception) { throw PdfAnnotationSourceException(failure) }
            }
        }
    }
}
