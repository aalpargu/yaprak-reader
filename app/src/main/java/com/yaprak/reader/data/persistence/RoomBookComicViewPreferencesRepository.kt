package com.yaprak.reader.data.persistence

import android.database.Cursor
import androidx.room.Transactor.SQLiteTransactionType
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One durable CBZ field; Room/current hash/format and native types are proved before defaults or writes. */
class RoomBookComicViewPreferencesRepository(
    private val database: LibraryDatabase,
    private val fileMutex: Mutex = Mutex(),
    private val validateAccess: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BookComicViewPreferencesRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.invalidationTracker.createFlow("books", "book_cbz_view_preferences")
            .catch { failure -> currentCoroutineContext().ensureActive(); throw BookComicViewPreferencesException(BookComicViewPreferencesFailure.SOURCE, failure) }
            .map {
                validateIdentity(bookId, contentSha256)
                transaction(write = false) { sql, check -> read(sql, bookId, contentSha256, check) }
            }

    override suspend fun saveSpread(bookId: String, contentSha256: String, doublePagePreferred: Boolean) {
        validateIdentity(bookId, contentSha256)
        transaction(write = true) { sql, check ->
            val before = read(sql, bookId, contentSha256, check)
            if (before.doublePagePreferred != doublePagePreferred) {
                check()
                sql.execSQL("INSERT OR REPLACE INTO book_cbz_view_preferences (bookId, contentSha256, doublePagePreferred) VALUES (?, ?, ?)",
                    arrayOf<Any>(bookId, contentSha256, if (doublePagePreferred) 1 else 0))
            }
            check()
        }
    }

    private fun read(sql: SupportSQLiteDatabase, bookId: String, hash: String, check: () -> Unit): BookComicViewPreferences =
        sql.query(SimpleSQLiteQuery("""
            SELECT b.id AS currentId, b.sha256 AS currentHash, b.format AS currentFormat,
                c.bookId AS savedId, c.contentSha256 AS savedHash, c.doublePagePreferred AS spread
            FROM books b LEFT JOIN book_cbz_view_preferences c ON c.bookId=b.id WHERE b.id=?
        """.trimIndent(), arrayOf(bookId))).use { cursor ->
            check()
            if (!cursor.moveToFirst()) fail(BookComicViewPreferencesFailure.STALE_BOOK)
            fun column(name: String) = cursor.getColumnIndexOrThrow(name)
            fun text(name: String): String {
                val index = column(name)
                if (cursor.getType(index) != Cursor.FIELD_TYPE_STRING) fail(BookComicViewPreferencesFailure.SOURCE)
                return cursor.getString(index)
            }
            if (text("currentId") != bookId || text("currentHash") != hash || text("currentFormat") !in setOf(BookFormat.CBZ.name, BookFormat.CBR.name))
                fail(BookComicViewPreferencesFailure.STALE_BOOK)
            if (cursor.isNull(column("savedId"))) {
                if (!cursor.isNull(column("savedHash")) || !cursor.isNull(column("spread"))) fail(BookComicViewPreferencesFailure.SOURCE)
                BookComicViewPreferences()
            } else {
                if (text("savedId") != bookId || !hashPattern.matches(text("savedHash"))) fail(BookComicViewPreferencesFailure.SOURCE)
                if (text("savedHash") != hash) fail(BookComicViewPreferencesFailure.STALE_BOOK)
                val index = column("spread")
                if (cursor.getType(index) != Cursor.FIELD_TYPE_INTEGER) fail(BookComicViewPreferencesFailure.SOURCE)
                val value = cursor.getLong(index)
                if (value !in 0L..1L) fail(BookComicViewPreferencesFailure.SOURCE)
                BookComicViewPreferences(value == 1L)
            }.also { check() }
        }

    private suspend fun <T> transaction(write: Boolean, block: (SupportSQLiteDatabase, () -> Unit) -> T): T {
        val caller = currentCoroutineContext()
        return withContext(io) {
            try {
                fileMutex.withLock {
                    caller.ensureActive(); validateAccess()
                    suspend fun work(): T {
                        val lease = currentCoroutineContext()
                        val check = { caller.ensureActive(); lease.ensureActive() }
                        check()
                        return block(database.openHelper.writableDatabase, check).also { check() }
                    }
                    if (write) database.useWriterConnection { connection ->
                        connection.withTransaction(SQLiteTransactionType.IMMEDIATE) { work() }
                    } else database.useReaderConnection { connection ->
                        connection.withTransaction(SQLiteTransactionType.DEFERRED) { work() }
                    }
                }
            } catch (cancelled: CancellationException) {
                caller.ensureActive(); throw BookComicViewPreferencesException(BookComicViewPreferencesFailure.SOURCE, cancelled)
            } catch (failure: BookComicViewPreferencesException) { throw failure }
            catch (failure: Exception) { throw BookComicViewPreferencesException(BookComicViewPreferencesFailure.SOURCE, failure) }
        }
    }
    private fun validateIdentity(bookId: String, hash: String) {
        if (bookId.isBlank() || bookId.length > 200 || bookId.any { it.isISOControl() || it.isSurrogate() } || !hashPattern.matches(hash))
            fail(BookComicViewPreferencesFailure.INVALID_VALUE)
    }
    private fun fail(reason: BookComicViewPreferencesFailure): Nothing = throw BookComicViewPreferencesException(reason)
    private companion object { val hashPattern = Regex("[0-9a-f]{64}") }
}
