package com.yaprak.reader.data.persistence

import android.database.Cursor
import androidx.room.Transactor.SQLiteTransactionType
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.*
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

/** Field-specific writes preserve the latest unrelated choices, including another reader's saves. */
class RoomBookPdfViewPreferencesRepository(
    private val database: LibraryDatabase,
    private val fileMutex: Mutex = Mutex(),
    private val validateAccess: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BookPdfViewPreferencesRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.invalidationTracker.createFlow("books", "book_pdf_view_preferences", "book_pdf_navigation_preferences")
            .catch { failure ->
                currentCoroutineContext().ensureActive()
                throw BookPdfViewPreferencesException(BookPdfViewPreferencesFailure.SOURCE, failure)
            }.map {
                validateIdentity(bookId, contentSha256)
                transaction(write = false) { sql, checkActive ->
                    val stored = find(sql, bookId, checkActive)
                    checkActive()
                    read(stored, bookId, contentSha256)
                }
            }

    override suspend fun saveCrop(bookId: String, contentSha256: String,
        horizontalPercent: Int, verticalPercent: Int) {
        if (horizontalPercent !in 0..MAX_PDF_CROP_PERCENT || verticalPercent !in 0..MAX_PDF_CROP_PERCENT)
            fail(BookPdfViewPreferencesFailure.INVALID_VALUE)
        update(bookId, contentSha256) { it.copy(horizontalPercent = horizontalPercent, verticalPercent = verticalPercent) }
    }

    override suspend fun saveFit(bookId: String, contentSha256: String, fitMode: PdfFitPreference) {
        update(bookId, contentSha256) { it.copy(fitMode = fitMode) }
    }

    override suspend fun saveSpread(bookId: String, contentSha256: String, doublePagePreferred: Boolean) {
        update(bookId, contentSha256) { it.copy(doublePagePreferred = doublePagePreferred) }
    }

    override suspend fun saveNavigation(bookId: String, contentSha256: String, mode: PdfNavigationMode) {
        validateIdentity(bookId, contentSha256)
        transaction(write = true) { sql, checkActive ->
            val stored = find(sql, bookId, checkActive)
            checkActive()
            val before = read(stored, bookId, contentSha256)
            if (before.navigationMode != mode) {
                checkActive()
                sql.execSQL("""INSERT OR REPLACE INTO book_pdf_navigation_preferences
                    (bookId, contentSha256, mode) VALUES (?, ?, ?)""".trimIndent(),
                    arrayOf<Any>(bookId, contentSha256, mode.name))
            }
            checkActive()
        }
    }

    private suspend fun update(bookId: String, contentSha256: String,
        change: (BookPdfViewPreferences) -> BookPdfViewPreferences) {
        validateIdentity(bookId, contentSha256)
        transaction(write = true) { sql, checkActive ->
            val stored = find(sql, bookId, checkActive)
            checkActive()
            val before = read(stored, bookId, contentSha256)
            val after = change(before)
            // Repeated explicit commands still reset native zoom/pan, but need no duplicate DB write.
            if (before != after) {
                checkActive()
                sql.execSQL("""INSERT OR REPLACE INTO book_pdf_view_preferences
                    (bookId, contentSha256, horizontalPercent, verticalPercent, fitMode, doublePagePreferred)
                    VALUES (?, ?, ?, ?, ?, ?)""".trimIndent(), arrayOf<Any>(bookId, contentSha256,
                    after.horizontalPercent, after.verticalPercent, after.fitMode.name,
                    if (after.doublePagePreferred) 1 else 0))
            }
            checkActive()
        }
    }

    private fun read(record: BookPdfViewPreferencesStoredRecord?, bookId: String,
        contentSha256: String): BookPdfViewPreferences {
        if (record == null) fail(BookPdfViewPreferencesFailure.STALE_BOOK)
        if (record.currentBookIdType != "text" || record.currentContentSha256Type != "text" ||
            record.currentFormatType != "text") fail(BookPdfViewPreferencesFailure.SOURCE)
        if (record.currentBookId != bookId || record.currentContentSha256 != contentSha256 ||
            record.currentFormat != BookFormat.PDF.name) fail(BookPdfViewPreferencesFailure.STALE_BOOK)
        // Both optional rows are checked independently. An absent crop/fit row cannot hide a bad mode.
        val navigation = record.navigation?.let { saved ->
            if (record.navigationBookIdType != "text" || record.navigationContentSha256Type != "text" ||
                record.navigationModeType != "text" || saved.bookId != bookId ||
                !hashPattern.matches(saved.contentSha256)) fail(BookPdfViewPreferencesFailure.SOURCE)
            if (saved.contentSha256 != contentSha256) fail(BookPdfViewPreferencesFailure.STALE_BOOK)
            PdfNavigationMode.entries.firstOrNull { it.name == saved.mode }
                ?: fail(BookPdfViewPreferencesFailure.SOURCE)
        } ?: PdfNavigationMode.PAGED
        val saved = record.preferences ?: return BookPdfViewPreferences(navigationMode = navigation)
        if (record.storedBookIdType != "text" || record.storedContentSha256Type != "text" ||
            saved.bookId != bookId || !hashPattern.matches(saved.contentSha256)) fail(BookPdfViewPreferencesFailure.SOURCE)
        // A stale row is unavailable, not an absent preference or a silently repaired default.
        if (saved.contentSha256 != contentSha256) fail(BookPdfViewPreferencesFailure.STALE_BOOK)
        if (record.horizontalType != "integer" || record.verticalType != "integer" ||
            record.fitType != "text" || record.spreadType != "integer" ||
            record.horizontalValue != saved.horizontalPercent.toLong() ||
            record.verticalValue != saved.verticalPercent.toLong() ||
            record.spreadValue != saved.doublePagePreferred.toLong() ||
            saved.horizontalPercent !in 0..MAX_PDF_CROP_PERCENT ||
            saved.verticalPercent !in 0..MAX_PDF_CROP_PERCENT || saved.doublePagePreferred !in 0..1)
            fail(BookPdfViewPreferencesFailure.SOURCE)
        val fit = PdfFitPreference.entries.firstOrNull { it.name == saved.fitMode }
            ?: fail(BookPdfViewPreferencesFailure.SOURCE)
        return BookPdfViewPreferences(saved.horizontalPercent, saved.verticalPercent, fit,
            saved.doublePagePreferred == 1, navigation)
    }

    /** Public Room leases refuse a closed source; all framework SQLite work stays synchronous. */
    private suspend fun <T> transaction(write: Boolean, block: (SupportSQLiteDatabase, () -> Unit) -> T): T {
        val caller = currentCoroutineContext()
        return withContext(io) {
            try {
                fileMutex.withLock {
                    caller.ensureActive()
                    validateAccess() // Do not await startup while holding its cleanup mutex.
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
                }
            } catch (cancelled: CancellationException) {
                caller.ensureActive()
                throw BookPdfViewPreferencesException(BookPdfViewPreferencesFailure.SOURCE, cancelled)
            } catch (failure: BookPdfViewPreferencesException) {
                throw failure
            } catch (failure: Exception) {
                throw BookPdfViewPreferencesException(BookPdfViewPreferencesFailure.SOURCE, failure)
            }
        }
    }

    private fun find(sql: SupportSQLiteDatabase, bookId: String, checkActive: () -> Unit): BookPdfViewPreferencesStoredRecord? =
        sql.query(SimpleSQLiteQuery(BOOK_PDF_VIEW_PREFERENCES_QUERY, arrayOf(bookId))).use { cursor ->
            checkActive()
            if (cursor.moveToFirst()) cursor.record() else null
        }

    private fun Cursor.record(): BookPdfViewPreferencesStoredRecord {
        fun text(name: String) = getString(getColumnIndexOrThrow(name))
        fun int(name: String) = getInt(getColumnIndexOrThrow(name))
        fun nullableLong(name: String): Long? {
            val column = getColumnIndexOrThrow(name)
            return if (isNull(column)) null else getLong(column)
        }
        val saved = if (isNull(getColumnIndexOrThrow("bookId"))) null else
            BookPdfViewPreferencesEntity(text("bookId"), text("contentSha256"), int("horizontalPercent"),
                int("verticalPercent"), text("fitMode"), int("doublePagePreferred"))
        val navigation = if (isNull(getColumnIndexOrThrow("navigation_bookId"))) null else
            BookPdfNavigationPreferencesEntity(text("navigation_bookId"), text("navigation_contentSha256"),
                text("navigation_mode"))
        return BookPdfViewPreferencesStoredRecord(text("currentBookId"), text("currentContentSha256"),
            text("currentFormat"), text("currentBookIdType"), text("currentContentSha256Type"),
            text("currentFormatType"), saved, text("storedBookIdType"), text("storedContentSha256Type"),
            text("horizontalType"), text("verticalType"), text("fitType"), text("spreadType"),
            nullableLong("horizontalValue"), nullableLong("verticalValue"), nullableLong("spreadValue"),
            navigation, text("navigationBookIdType"), text("navigationContentSha256Type"), text("navigationModeType"))
    }

    private fun validateIdentity(bookId: String, contentSha256: String) {
        if (bookId.isBlank() || bookId.length > 200 || bookId.any { it.isISOControl() || it.isSurrogate() } ||
            !hashPattern.matches(contentSha256))
            fail(BookPdfViewPreferencesFailure.INVALID_VALUE)
    }
    private fun fail(reason: BookPdfViewPreferencesFailure): Nothing = throw BookPdfViewPreferencesException(reason)
    private companion object { val hashPattern = Regex("[0-9a-f]{64}") }
}
