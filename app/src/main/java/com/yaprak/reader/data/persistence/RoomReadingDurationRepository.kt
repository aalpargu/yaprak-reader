package com.yaprak.reader.data.persistence

import android.database.Cursor
import androidx.room.Transactor.SQLiteTransactionType
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.statistics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Measures nothing itself: only validated, cumulative caller receipts become durable totals. */
class RoomReadingDurationRepository(
    private val database: LibraryDatabase,
    private val fileMutex: Mutex = Mutex(),
    private val validateAccess: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ReadingDurationRepository {
    override fun observe() = database.invalidationTracker.createFlow("books", "reading_duration_receipts",
        "reading_progress", "book_organizations", "book_covers")
        .catch { failure ->
            currentCoroutineContext().ensureActive()
            throw ReadingDurationException(ReadingDurationFailure.SOURCE, failure)
        }.map {
            transaction(write = false, captureBooks = true) { sql, checkActive, books ->
                // A direct Room mutation may not share fileMutex. Recheck identities in this
                // transaction so a removed/replaced book cannot credit a captured old hash.
                val identities = sql.query("SELECT id, sha256 FROM books").use { cursor ->
                    buildMap { while (cursor.moveToNext()) { checkActive(); put(cursor.text("id"), cursor.text("sha256")) } }
                }
                val currentBooks = books.filter { identities[it.id] == it.sha256 }
                val hashes = currentBooks.associate { it.id to it.sha256 }
                val perBook = mutableMapOf<String, Long>()
                val perDay = sortedMapOf<String, Long>()
                var total = 0L
                var count = 0
                // Validate raw values before arithmetic; SQLite SUM would coerce malformed values.
                sql.query("SELECT * FROM reading_duration_receipts ORDER BY id").use { cursor ->
                    while (cursor.moveToNext()) {
                        checkActive()
                        if (++count > MAX_READING_DURATION_RECEIPTS) fail(ReadingDurationFailure.LIMIT)
                        val receipt = cursor.receipt()
                        if (hashes[receipt.bookId] != receipt.contentSha256) continue
                        perBook[receipt.bookId] = (perBook[receipt.bookId] ?: 0L) + receipt.elapsedMillis
                        perDay[receipt.localDate] = (perDay[receipt.localDate] ?: 0L) + receipt.elapsedMillis
                        total += receipt.elapsedMillis
                    }
                }
                ReadingDurationSnapshot(perDay.map { ReadingDurationDay(it.key, it.value) },
                    currentBooks.map { BookReadingDuration(it, perBook[it.id] ?: 0L) }, total)
            }
        }

    override suspend fun record(receipt: ReadingDurationReceipt) {
        validateReadingDurationReceipt(receipt)
        transaction(write = true) { sql, checkActive, _ ->
            val currentHash = sql.query(SimpleSQLiteQuery("SELECT sha256 FROM books WHERE id = ?",
                arrayOf(receipt.bookId))).use { cursor ->
                checkActive()
                if (cursor.moveToFirst()) cursor.text("sha256") else null
            }
            if (currentHash != receipt.contentSha256) fail(ReadingDurationFailure.STALE_BOOK)
            fun existing(): ReadingDurationReceipt? = sql.query(SimpleSQLiteQuery(
                "SELECT * FROM reading_duration_receipts WHERE id = ? LIMIT 1", arrayOf(receipt.id))).use { cursor ->
                checkActive(); if (cursor.moveToFirst()) cursor.receipt() else null
            }
            if (existing() == null) {
                val count = sql.query("SELECT COUNT(*) AS receiptCount FROM reading_duration_receipts").use { cursor ->
                    checkActive(); if (!cursor.moveToFirst()) fail(ReadingDurationFailure.SOURCE)
                    cursor.number("receiptCount")
                }
                if (count >= MAX_READING_DURATION_RECEIPTS) fail(ReadingDurationFailure.LIMIT)
                checkActive()
                // API26 SQLite supports this syntax. REPLACE must never retarget an existing receipt.
                sql.execSQL("""INSERT OR IGNORE INTO reading_duration_receipts
                    (id, bookId, contentSha256, localDate, zoneId, startedAt, endedAt, elapsedMillis)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(), arrayOf<Any>(receipt.id, receipt.bookId,
                    receipt.contentSha256, receipt.localDate, receipt.zoneId, receipt.startedAt,
                    receipt.endedAt, receipt.elapsedMillis))
            }
            val stored = existing() ?: fail(ReadingDurationFailure.SOURCE)
            if (stored.copy(endedAt = receipt.endedAt, elapsedMillis = receipt.elapsedMillis) != receipt) {
                fail(ReadingDurationFailure.INVALID_RECORD)
            }
            if (receipt.elapsedMillis >= stored.elapsedMillis &&
                (receipt.elapsedMillis > stored.elapsedMillis || receipt.endedAt > stored.endedAt)) {
                checkActive()
                sql.execSQL("""UPDATE reading_duration_receipts SET elapsedMillis = MAX(elapsedMillis, ?),
                    endedAt = MAX(endedAt, ?) WHERE id = ?""".trimIndent(),
                    arrayOf<Any>(receipt.elapsedMillis, receipt.endedAt, receipt.id))
            }
        }
    }

    /** A Room public lease prevents reopening a closed source. All statements remain synchronous. */
    private suspend fun <T> transaction(write: Boolean, captureBooks: Boolean = false,
        block: (SupportSQLiteDatabase, () -> Unit, List<Book>) -> T): T {
        val caller = currentCoroutineContext()
        return withContext(io) { try {
            fileMutex.withLock {
                caller.ensureActive()
                validateAccess() // Startup recovery uses this mutex; never await it while holding the lock.
                // Finish the Room flow before entering the thread-confined framework transaction.
                val books = if (captureBooks) RoomLibraryRepository(database.books()).observeBooks().first() else emptyList()
                caller.ensureActive()
                suspend fun work(): T {
                    val lease = currentCoroutineContext()
                    val checkActive = { lease.ensureActive(); caller.ensureActive() }
                    checkActive()
                    return block(database.openHelper.writableDatabase, checkActive, books).also { checkActive() }
                }
                if (write) database.useWriterConnection { connection ->
                    connection.withTransaction(SQLiteTransactionType.IMMEDIATE) { work() }
                } else database.useReaderConnection { connection ->
                    connection.withTransaction(SQLiteTransactionType.DEFERRED) { work() }
                }
            }
        } catch (cancelled: CancellationException) {
            caller.ensureActive()
            throw ReadingDurationException(ReadingDurationFailure.SOURCE, cancelled)
        } catch (failure: ReadingDurationException) { throw failure }
        catch (failure: Exception) { throw ReadingDurationException(ReadingDurationFailure.SOURCE, failure) }
        }
    }

    private fun Cursor.receipt(): ReadingDurationReceipt {
        val receipt = ReadingDurationReceipt(text("id"), text("bookId"), text("contentSha256"),
            text("localDate"), text("zoneId"), number("startedAt"), number("endedAt"), number("elapsedMillis"))
        try { validateReadingDurationReceipt(receipt) }
        catch (failure: ReadingDurationException) { throw ReadingDurationException(ReadingDurationFailure.SOURCE, failure) }
        return receipt
    }

    private fun Cursor.text(name: String): String {
        val index = getColumnIndexOrThrow(name)
        if (getType(index) != Cursor.FIELD_TYPE_STRING) fail(ReadingDurationFailure.SOURCE)
        return getString(index)
    }
    private fun Cursor.number(name: String): Long {
        val index = getColumnIndexOrThrow(name)
        if (getType(index) != Cursor.FIELD_TYPE_INTEGER) fail(ReadingDurationFailure.SOURCE)
        return getLong(index)
    }
    private fun fail(reason: ReadingDurationFailure): Nothing = throw ReadingDurationException(reason)
}
