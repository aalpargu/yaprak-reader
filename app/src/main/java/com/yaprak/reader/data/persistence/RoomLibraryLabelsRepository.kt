package com.yaprak.reader.data.persistence

import android.database.Cursor
import androidx.room.Transactor.SQLiteTransactionType
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yaprak.reader.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/** Changes only the selected JSON column; no status transition or completion event is produced. */
class RoomLibraryLabelsRepository(private val database: LibraryDatabase,
    private val fileMutex: Mutex = Mutex(), private val validateAccess: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO) : LibraryLabelsRepository {
    override fun observe(): Flow<LibraryLabelSnapshot> = database.invalidationTracker
        .createFlow("books", "book_organizations").catch { failure ->
            currentCoroutineContext().ensureActive(); throw LibraryLabelException(LibraryLabelFailure.SOURCE, failure)
        }.map { transaction(false) { sql, active -> LibraryLabelSnapshot(rows(sql, active)) } }

    override suspend fun change(kind: LibraryLabelKind, source: String, target: String?,
        expected: List<LibraryLabelMember>): Int {
        require(canonicalLibraryLabel(source) == source)
        if (target != null) require(canonicalLibraryLabel(target) == target)
        if (expected.isEmpty() || expected.size > MAX_LIBRARY_LABEL_ROWS) fail(LibraryLabelFailure.CONFLICT)
        expected.forEach(::validateLibraryLabelMember)
        require(expected.map { it.bookId }.distinct().size == expected.size)
        // Own all expected values before suspending; callers cannot alter a pending operation's list.
        val captured = LibraryLabelSnapshot(expected).members.sortedBy { it.bookId }
        val sourceKey = libraryLabelKey(source)
        return transaction(true) { sql, active ->
            val current = rows(sql, active).filter { row ->
                kind.names(row.organization).any { libraryLabelKey(it) == sourceKey }
            }
            if (current.sortedBy { it.bookId } != captured) fail(LibraryLabelFailure.CONFLICT)
            val targetKey = target?.let(::libraryLabelKey)
            val column = when (kind) { LibraryLabelKind.TAG -> "tagsJson"; LibraryLabelKind.COLLECTION -> "collectionsJson" }
            for (row in current) {
                active()
                val names = kind.names(row.organization)
                val edited = if (target == null) names.filterNot { libraryLabelKey(it) == sourceKey }
                    else names.map { if (libraryLabelKey(it) == sourceKey || libraryLabelKey(it) == targetKey) target else it }
                val normalized = when (kind) {
                    LibraryLabelKind.TAG -> row.organization.copy(tags = edited).normalized().tags
                    LibraryLabelKind.COLLECTION -> row.organization.copy(collections = edited).normalized().collections
                }
                sql.execSQL("UPDATE book_organizations SET " + column + " = ? WHERE bookId = ? AND contentSha256 = ?",
                    arrayOf<Any>(JSONArray(normalized).toString(), row.bookId, row.contentSha256))
                sql.query("SELECT changes()").use { cursor ->
                    active(); if (!cursor.moveToFirst() || cursor.getLong(0) != 1L) fail(LibraryLabelFailure.CONFLICT)
                }
            }
            active(); current.size
        }
    }
    private fun rows(sql: SupportSQLiteDatabase, active: () -> Unit): List<LibraryLabelMember> =
        sql.query("""SELECT e.* FROM book_organizations e JOIN books b
            ON b.id = e.bookId AND b.sha256 = e.contentSha256
            ORDER BY e.bookId LIMIT 10001""".trimIndent()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    active(); if (size >= MAX_LIBRARY_LABEL_ROWS) fail(LibraryLabelFailure.LIMIT)
                    val favoriteIndex = cursor.getColumnIndexOrThrow("favorite")
                    if (cursor.getType(favoriteIndex) != Cursor.FIELD_TYPE_INTEGER || cursor.getLong(favoriteIndex) !in 0L..1L)
                        fail(LibraryLabelFailure.SOURCE)
                    val entity = BookOrganizationEntity(cursor.text("bookId"), cursor.text("contentSha256"),
                        cursor.text("readingStatus"), cursor.getLong(favoriteIndex) == 1L,
                        cursor.optionalText("series"), cursor.optionalText("genre"),
                        cursor.text("tagsJson"), cursor.text("collectionsJson"))
                    add(LibraryLabelMember(entity.bookId, entity.contentSha256, entity.toOrganization())
                        .also(::validateLibraryLabelMember))
                }
            }
        }
    private suspend fun <T> transaction(write: Boolean, block: (SupportSQLiteDatabase, () -> Unit) -> T): T {
        val caller = currentCoroutineContext()
        return withContext(io) { try {
            fileMutex.withLock {
                caller.ensureActive(); validateAccess()
                suspend fun work(): T {
                    val lease = currentCoroutineContext()
                    val active = { caller.ensureActive(); lease.ensureActive() }
                    active(); return block(database.openHelper.writableDatabase, active).also { active() }
                }
                if (write) database.useWriterConnection { connection ->
                    connection.withTransaction(SQLiteTransactionType.IMMEDIATE) { work() }
                } else database.useReaderConnection { connection ->
                    connection.withTransaction(SQLiteTransactionType.DEFERRED) { work() }
                }
            }
        } catch (cancelled: CancellationException) {
            caller.ensureActive(); throw LibraryLabelException(LibraryLabelFailure.SOURCE, cancelled)
        } catch (failure: LibraryLabelException) { throw failure }
        catch (failure: Exception) { throw LibraryLabelException(LibraryLabelFailure.SOURCE, failure) }
        }
    }
    private fun Cursor.text(name: String): String {
        val index = getColumnIndexOrThrow(name)
        if (getType(index) != Cursor.FIELD_TYPE_STRING) fail(LibraryLabelFailure.SOURCE)
        return getString(index)
    }
    private fun Cursor.optionalText(name: String): String? =
        if (isNull(getColumnIndexOrThrow(name))) null else text(name)
    private fun fail(reason: LibraryLabelFailure): Nothing = throw LibraryLabelException(reason)
}
