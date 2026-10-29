package com.yaprak.reader.data.persistence

import android.database.Cursor
import androidx.room.Transactor.SQLiteTransactionType
import androidx.room.useReaderConnection
import com.yaprak.reader.domain.statistics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Read-only, current-library history. All raw records are validated before totals are exposed. */
class RoomBookCompletionRepository(private val database: LibraryDatabase,
    private val fileMutex: Mutex = Mutex(), private val validateAccess: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BookCompletionRepository {
    override fun observe(): Flow<BookCompletionSnapshot> = database.invalidationTracker
        .createFlow("books", "book_completion_events")
        .catch { failure ->
            currentCoroutineContext().ensureActive()
            throw BookCompletionException(BookCompletionFailure.SOURCE, failure)
        }.map {
        val caller = currentCoroutineContext()
        withContext(io) {
            try {
                fileMutex.withLock {
                    caller.ensureActive()
                    validateAccess()
                    database.useReaderConnection<BookCompletionSnapshot> { connection ->
                        connection.withTransaction<BookCompletionSnapshot>(SQLiteTransactionType.DEFERRED) {
                            val lease = currentCoroutineContext()
                            val entries = ArrayList<BookCompletionEntry>()
                            var count = 0
                            database.openHelper.writableDatabase.query(
                                """SELECT e.*, b.title, b.authors FROM book_completion_events e
                                    JOIN books b ON b.id = e.bookId AND b.sha256 = e.contentSha256
                                    ORDER BY e.completedAt DESC, e.id DESC""".trimIndent()
                            ).use { cursor ->
                                while (cursor.moveToNext()) {
                                    lease.ensureActive(); caller.ensureActive()
                                    if (++count > MAX_BOOK_COMPLETION_EVENTS) throw BookCompletionException(BookCompletionFailure.LIMIT)
                                    val event = BookCompletionEvent(cursor.text("id"), cursor.text("bookId"),
                                        cursor.text("contentSha256"), cursor.number("completedAt"),
                                        cursor.text("localDate"), cursor.text("zoneId"))
                                    validateBookCompletionEvent(event)
                                    val title = cursor.text("title")
                                    val authorIndex = cursor.getColumnIndexOrThrow("authors")
                                    val authors = if (cursor.isNull(authorIndex)) null else cursor.text("authors")
                                    if (entries.size < BOOK_COMPLETION_VISIBLE_LIMIT)
                                        entries += BookCompletionEntry(event, title, authors)
                                }
                            }
                            lease.ensureActive(); caller.ensureActive()
                            BookCompletionSnapshot(count, entries)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                caller.ensureActive()
                throw BookCompletionException(BookCompletionFailure.SOURCE, cancelled)
            } catch (failure: BookCompletionException) { throw failure }
            catch (failure: Exception) { throw BookCompletionException(BookCompletionFailure.SOURCE, failure) }
        }
    }
    private fun Cursor.text(name: String): String {
        val index = getColumnIndexOrThrow(name)
        if (getType(index) != Cursor.FIELD_TYPE_STRING) throw BookCompletionException(BookCompletionFailure.SOURCE)
        return getString(index)
    }
    private fun Cursor.number(name: String): Long {
        val index = getColumnIndexOrThrow(name)
        if (getType(index) != Cursor.FIELD_TYPE_INTEGER) throw BookCompletionException(BookCompletionFailure.SOURCE)
        return getLong(index)
    }
}
