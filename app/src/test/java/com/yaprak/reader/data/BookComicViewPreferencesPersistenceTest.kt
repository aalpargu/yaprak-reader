package com.yaprak.reader.data

import android.app.Application
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.settings.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookComicViewPreferencesPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val comic = Book("comic", "Comic", null, BookFormat.CBZ, "comic.cbz", "comic.cbz", 100, "a".repeat(64), 1, 5)
    private suspend fun refusal(reason: BookComicViewPreferencesFailure, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected $reason, got $error", error is BookComicViewPreferencesException)
        assertEquals(reason, (error as BookComicViewPreferencesException).reason)
    }
    @Test fun savedSingleAndDoubleSurviveReopenAndPreserveOtherBooksAndEpubModes() = runBlocking {
        val name = "cp-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            val epub = comic.copy(id = "epub", format = BookFormat.EPUB, pageCount = null, sha256 = "c".repeat(64))
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                val library = RoomLibraryRepository(db.books()); library.add(comic); library.add(epub)
                library.add(comic.copy(id = "another", sha256 = "d".repeat(64)))
                val repository = RoomBookComicViewPreferencesRepository(db)
                assertFalse(repository.observe(comic.id, comic.sha256).first().doublePagePreferred)
                repository.saveSpread(comic.id, comic.sha256, true); repository.saveSpread("another", "d".repeat(64), true)
                RoomBookReadingModeRepository(db).save(epub.id, epub.sha256, BookReadingMode.SCROLL)
                RoomBookTextAlignmentRepository(db).save(epub.id, epub.sha256, ReadingTextAlignment.JUSTIFY)
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                val repository = RoomBookComicViewPreferencesRepository(db)
                assertTrue(repository.observe(comic.id, comic.sha256).first().doublePagePreferred)
                repository.saveSpread(comic.id, comic.sha256, false)
                assertFalse(repository.observe(comic.id, comic.sha256).first().doublePagePreferred)
                assertTrue(repository.observe("another", "d".repeat(64)).first().doublePagePreferred)
                assertEquals(BookReadingMode.SCROLL, RoomBookReadingModeRepository(db).observe(epub.id, epub.sha256).first())
                assertEquals(ReadingTextAlignment.JUSTIFY, RoomBookTextAlignmentRepository(db).observe(epub.id, epub.sha256).first())
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                assertFalse(RoomBookComicViewPreferencesRepository(db).observe(comic.id, comic.sha256).first().doublePagePreferred)
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }
    @Test fun missingChangedHashOtherFormatsAndCascadeCannotReuseOrRepairAStoredChoice() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val library = RoomLibraryRepository(db.books()); library.add(comic)
            for (format in listOf(BookFormat.EPUB, BookFormat.TXT, BookFormat.PDF))
                library.add(comic.copy(id = format.name, format = format, sha256 = (format.ordinal + 1).toString().repeat(64),
                    pageCount = if (format == BookFormat.PDF) 5 else null))
            val repository = RoomBookComicViewPreferencesRepository(db)
            repository.saveSpread(comic.id, comic.sha256, true)
            for (id in listOf("missing", "EPUB", "TXT", "PDF")) {
                refusal(BookComicViewPreferencesFailure.STALE_BOOK) { repository.observe(id, comic.sha256).first() }
                refusal(BookComicViewPreferencesFailure.STALE_BOOK) { repository.saveSpread(id, comic.sha256, true) }
            }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE books SET sha256=? WHERE id=?", arrayOf("b".repeat(64), comic.id))
            for (hash in listOf(comic.sha256, "b".repeat(64))) {
                refusal(BookComicViewPreferencesFailure.STALE_BOOK) { repository.observe(comic.id, hash).first() }
                refusal(BookComicViewPreferencesFailure.STALE_BOOK) { repository.saveSpread(comic.id, hash, false) }
            }
            assertEquals(comic.sha256, sql.compileStatement("SELECT contentSha256 FROM book_cbz_view_preferences").simpleQueryForString())
            sql.execSQL("DELETE FROM books WHERE id=?", arrayOf(comic.id))
            assertEquals(0L, sql.compileStatement("SELECT COUNT(*) FROM book_cbz_view_preferences").simpleQueryForLong())
        }
    }
    @Test fun nativeNumericOrNonIntegerCorruptionIsRefusedWithoutSilentlyRepairingTheRow() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(comic); val repository = RoomBookComicViewPreferencesRepository(db)
            repository.saveSpread(comic.id, comic.sha256, true); val sql = db.openHelper.writableDatabase
            for (value in listOf<Any>(-1, 2, 2147483648L, 1.5, "true", byteArrayOf(1))) {
                sql.execSQL("UPDATE book_cbz_view_preferences SET doublePagePreferred=?", arrayOf(value))
                val before = rows(sql.query("SELECT typeof(doublePagePreferred), hex(doublePagePreferred) FROM book_cbz_view_preferences"))
                refusal(BookComicViewPreferencesFailure.SOURCE) { repository.observe(comic.id, comic.sha256).first() }
                refusal(BookComicViewPreferencesFailure.SOURCE) { repository.saveSpread(comic.id, comic.sha256, false) }
                assertEquals(before, rows(sql.query("SELECT typeof(doublePagePreferred), hex(doublePagePreferred) FROM book_cbz_view_preferences")))
            }
            sql.execSQL("UPDATE book_cbz_view_preferences SET doublePagePreferred=1")
            assertTrue(repository.observe(comic.id, comic.sha256).first().doublePagePreferred)
        }
    }
    @Test fun abortedInsertRecoveryGuardAndInvalidInputLeaveCommittedPreferenceIntact() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(comic); var allowed = true
            val repository = RoomBookComicViewPreferencesRepository(db, validateAccess = { check(allowed) })
            repository.saveSpread(comic.id, comic.sha256, true); val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER refuse_comic BEFORE INSERT ON book_cbz_view_preferences BEGIN SELECT RAISE(ABORT, 'controlled'); END")
            refusal(BookComicViewPreferencesFailure.SOURCE) { repository.saveSpread(comic.id, comic.sha256, false) }
            assertTrue(repository.observe(comic.id, comic.sha256).first().doublePagePreferred)
            sql.execSQL("DROP TRIGGER refuse_comic"); allowed = false
            refusal(BookComicViewPreferencesFailure.SOURCE) { repository.saveSpread(comic.id, comic.sha256, false) }
            refusal(BookComicViewPreferencesFailure.SOURCE) { repository.observe(comic.id, comic.sha256).first() }
            allowed = true
            for ((id, hash) in listOf("" to comic.sha256, comic.id to "hash"))
                refusal(BookComicViewPreferencesFailure.INVALID_VALUE) { repository.saveSpread(id, hash, false) }
            assertTrue(repository.observe(comic.id, comic.sha256).first().doublePagePreferred)
        }
    }
    private fun rows(cursor: Cursor): List<List<String?>> = cursor.use {
        buildList { while (it.moveToNext()) add(List(it.columnCount) { column -> if (it.isNull(column)) null else it.getString(column) }) }
    }
}
