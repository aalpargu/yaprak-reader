package com.yaprak.reader.data.persistence

import android.content.ContentValues
import androidx.room.Transactor.SQLiteTransactionType
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yaprak.reader.domain.passages.*
import com.yaprak.reader.reader.EpubSourceAnchor
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceTextAnchor
import java.util.UUID
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

/** One atomic record for one exact source selection; original-node text/topology are never stored. */
class RoomEpubPassageRepository(
    private val database: LibraryDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fileMutex: Mutex = Mutex(),
    private val validateAccess: () -> Unit = {},
) : EpubPassageRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.invalidationTracker.createFlow("books", "epub_passages", "epub_range_passages").catch { failure ->
            if (failure is CancellationException) {
                currentCoroutineContext().ensureActive()
                throw IllegalStateException("EPUB passage source is closed", failure)
            }
            throw failure
        }.map {
            transaction(write = false) { sql, checkActive ->
                EpubPassageSql.current(sql, bookId, contentSha256, checkActive)
            }
        }

    override suspend fun add(bookId: String, contentSha256: String, text: String, anchor: EpubSourceAnchor) {
        EpubPassageSql.validate(text, anchor)
        transaction(write = true) { sql, checkActive ->
            verifyBook(sql, bookId, contentSha256, anchor)
            EpubPassageSql.checkDistinctIds(sql)
            check(!exists(sql, bookId, contentSha256, anchor)) { "EPUB passage already saved" }
            checkActive()
            insert(sql, bookId, contentSha256, EpubPassage(UUID.randomUUID().toString(), text,
                anchor, System.currentTimeMillis()))
        }
    }

    override suspend fun remove(bookId: String, contentSha256: String, id: String) {
        transaction(write = true) { sql, checkActive ->
            verifyBook(sql, bookId, contentSha256)
            EpubPassageSql.checkDistinctIds(sql)
            val actual = find(sql, bookId, contentSha256, id)
            checkNotNull(actual) { "EPUB passage missing" }
            checkActive()
            check(sql.delete(EpubPassageSql.table(actual.anchor), "id = ? AND bookId = ? AND contentSha256 = ?",
                arrayOf(id, bookId, contentSha256)) == 1) { "EPUB passage missing" }
        }
    }

    override suspend fun createAnnotation(bookId: String, contentSha256: String, text: String,
        anchor: EpubSourceAnchor, highlightColor: EpubHighlightColor?, attachedNote: String?): EpubPassage {
        EpubPassageSql.validate(text, anchor)
        require(isWellFormedEpubAnnotationText(text)) { "Invalid EPUB annotation text" }
        EpubPassageSql.validateNote(attachedNote)
        require(highlightColor != null || attachedNote != null) { "EPUB annotation is empty" }
        return transaction(write = true) { sql, checkActive ->
            verifyBook(sql, bookId, contentSha256, anchor)
            EpubPassageSql.checkDistinctIds(sql)
            if (exists(sql, bookId, contentSha256, anchor)) throw EpubPassageChangedException()
            requireAnnotationCapacity(sql, bookId, contentSha256)
            val passage = EpubPassage(UUID.randomUUID().toString(), text, anchor,
                System.currentTimeMillis(), highlightColor, attachedNote)
            checkActive()
            insert(sql, bookId, contentSha256, passage)
            passage
        }
    }

    override suspend fun updateAnnotation(bookId: String, contentSha256: String,
        expected: EpubPassage, highlightColor: EpubHighlightColor?, attachedNote: String?): EpubPassage {
        EpubPassageSql.validate(expected.text, expected.anchor)
        EpubPassageSql.validateNote(attachedNote)
        if (highlightColor != null || attachedNote != null)
            require(isWellFormedEpubAnnotationText(expected.text)) { "Invalid EPUB annotation text" }
        return transaction(write = true) { sql, checkActive ->
            verifyBook(sql, bookId, contentSha256, expected.anchor)
            EpubPassageSql.checkDistinctIds(sql)
            requireExpected(sql, bookId, contentSha256, expected)
            check(expected.revision in 0 until Long.MAX_VALUE) { "EPUB passage revision exhausted" }
            if (expected.highlightColor == null && expected.attachedNote == null &&
                (highlightColor != null || attachedNote != null)) requireAnnotationCapacity(sql, bookId, contentSha256)
            val next = expected.copy(highlightColor = highlightColor, attachedNote = attachedNote,
                revision = expected.revision + 1)
            val values = ContentValues(3).apply {
                put("highlightColor", highlightColor?.name); put("attachedNote", attachedNote); put("revision", next.revision)
            }
            checkActive()
            if (sql.update(EpubPassageSql.table(expected.anchor), 0, values,
                    "id = ? AND bookId = ? AND contentSha256 = ? AND revision = ?",
                    arrayOf(expected.id, bookId, contentSha256, expected.revision.toString())) != 1)
                throw EpubPassageChangedException()
            next
        }
    }

    override suspend fun removeExpected(bookId: String, contentSha256: String, expected: EpubPassage) {
        transaction(write = true) { sql, checkActive ->
            verifyBook(sql, bookId, contentSha256, expected.anchor)
            EpubPassageSql.checkDistinctIds(sql)
            requireExpected(sql, bookId, contentSha256, expected)
            checkActive()
            if (sql.delete(EpubPassageSql.table(expected.anchor),
                    "id = ? AND bookId = ? AND contentSha256 = ? AND revision = ?",
                    arrayOf(expected.id, bookId, contentSha256, expected.revision.toString())) != 1)
                throw EpubPassageChangedException()
        }
    }

    private suspend fun <T> transaction(write: Boolean,
        block: (SupportSQLiteDatabase, () -> Unit) -> T): T {
        val caller = currentCoroutineContext()
        return withContext(io) {
            fileMutex.withLock {
                caller.ensureActive()
                validateAccess() // Recovery uses the same mutex; this callback checks rather than awaits.
                try {
                    suspend fun work(): T {
                        val lease = currentCoroutineContext()
                        val checkActive = { lease.ensureActive(); caller.ensureActive() }
                        checkActive()
                        return block(database.openHelper.writableDatabase, checkActive).also { checkActive() }
                    }
                    if (write) database.useWriterConnection { connection ->
                        connection.withTransaction(SQLiteTransactionType.IMMEDIATE) { work() }
                    } else database.useReaderConnection { connection ->
                        connection.withTransaction(SQLiteTransactionType.DEFERRED) { work() }
                    }
                } catch (cancelled: CancellationException) {
                    caller.ensureActive()
                    throw IllegalStateException("EPUB passage source is closed", cancelled)
                }
            }
        }
    }

    private fun verifyBook(sql: SupportSQLiteDatabase, bookId: String, hash: String, anchor: EpubSourceAnchor? = null) {
        sql.query(SimpleSQLiteQuery("SELECT sha256, format FROM books WHERE id = ?", arrayOf(bookId))).use {
            check(it.moveToFirst() && it.getType(0) == android.database.Cursor.FIELD_TYPE_STRING &&
                it.getType(1) == android.database.Cursor.FIELD_TYPE_STRING && it.getString(0) == hash && it.getString(1) == "EPUB") {
                "EPUB passage book mismatch"
            }
        }
        if (anchor is SourceRangeAnchor) require(Regex("[0-9a-f]{64}").matches(hash)) { "Invalid EPUB range book hash" }
    }

    private fun find(sql: SupportSQLiteDatabase, bookId: String, hash: String, id: String): EpubPassage? {
        var actual: EpubPassage? = null
        listOf(EpubPassageSql.LEGACY, EpubPassageSql.RANGE).forEach { table ->
            sql.query(SimpleSQLiteQuery("SELECT * FROM $table WHERE id = ? AND bookId = ? AND contentSha256 = ?",
                arrayOf(id, bookId, hash))).use {
                if (it.moveToFirst()) {
                    check(actual == null) { "Conflicting EPUB passage identities" }
                    actual = EpubPassageSql.read(it, table == EpubPassageSql.RANGE)
                }
            }
        }
        return actual
    }

    private fun requireExpected(sql: SupportSQLiteDatabase, bookId: String, hash: String, expected: EpubPassage) {
        if (find(sql, bookId, hash, expected.id) != expected) throw EpubPassageChangedException()
    }

    private fun exists(sql: SupportSQLiteDatabase, bookId: String, hash: String, anchor: EpubSourceAnchor): Boolean =
        when (anchor) {
            is SourceTextAnchor -> sql.query(SimpleSQLiteQuery("""
                SELECT 1 FROM epub_passages WHERE bookId = ? AND contentSha256 = ? AND anchorVersion = 1
                  AND resourceHref = ? AND nodeTextSha256 = ? AND startOffset = ? AND endOffset = ?
            """.trimIndent(), arrayOf<Any>(bookId, hash, anchor.resourceHref, anchor.nodeTextSha256,
                anchor.start, anchor.end))).use { it.moveToFirst() }
            is SourceRangeAnchor -> sql.query(SimpleSQLiteQuery("""
                SELECT 1 FROM epub_range_passages WHERE bookId = ? AND contentSha256 = ? AND anchorKey = ?
            """.trimIndent(), arrayOf(bookId, hash, EpubRangeAnchorCodec.key(anchor)))).use { it.moveToFirst() }
        }

    private fun requireAnnotationCapacity(sql: SupportSQLiteDatabase, bookId: String, hash: String) {
        val count = sql.query(SimpleSQLiteQuery("""
            SELECT COUNT(*) FROM (
              SELECT id FROM epub_passages WHERE bookId = ? AND contentSha256 = ? AND (highlightColor IS NOT NULL OR attachedNote IS NOT NULL)
              UNION ALL
              SELECT id FROM epub_range_passages WHERE bookId = ? AND contentSha256 = ? AND (highlightColor IS NOT NULL OR attachedNote IS NOT NULL)
            )
        """.trimIndent(), arrayOf(bookId, hash, bookId, hash))).use { check(it.moveToFirst()); it.getLong(0) }
        if (count >= MAX_EPUB_ANNOTATIONS_PER_BOOK) throw EpubAnnotationLimitException()
    }

    private fun insert(sql: SupportSQLiteDatabase, bookId: String, hash: String, passage: EpubPassage) {
        listOf(EpubPassageSql.LEGACY, EpubPassageSql.RANGE).forEach { table ->
            sql.query(SimpleSQLiteQuery("SELECT 1 FROM $table WHERE id = ?", arrayOf(passage.id))).use {
                check(!it.moveToFirst()) { "Conflicting EPUB passage identities" }
            }
        }
        val anchor = passage.anchor
        val values = ContentValues(13).apply {
            put("id", passage.id); put("bookId", bookId); put("contentSha256", hash)
            put("resourceHref", anchor.resourceHref)
            when (anchor) {
                is SourceTextAnchor -> {
                    put("anchorVersion", 1); put("nodeTextSha256", anchor.nodeTextSha256)
                    put("startOffset", anchor.start); put("endOffset", anchor.end)
                }
                is SourceRangeAnchor -> {
                    put("anchorVersion", 2); put("anchorJson", EpubRangeAnchorCodec.encode(anchor))
                    put("anchorKey", EpubRangeAnchorCodec.key(anchor))
                }
            }
            put("text", passage.text); put("createdAt", passage.createdAt)
            put("highlightColor", passage.highlightColor?.name); put("attachedNote", passage.attachedNote)
            put("revision", passage.revision)
        }
        check(sql.insert(EpubPassageSql.table(anchor), 0, values) != -1L) { "EPUB passage could not be saved" }
    }
}
