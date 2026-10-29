package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.statistics.*
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BookCompletionPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("book", "Kitap", "Yazar", BookFormat.EPUB, "book.epub", "book.epub", 100, "a".repeat(64), 1)
    private val time = ZonedDateTime.parse("2026-03-29T03:15:00+02:00[Europe/Berlin]")
    private fun organization(status: BookReadingStatus, favorite: Boolean = false) = BookOrganization(status, favorite)
    @Test fun legacyCompletedStaysUndatedAndRealTransitionsAreAtomicIdempotentAndDurable() = runBlocking {
        val name = "bh-" + UUID.randomUUID().toString().take(8) + ".db"
        var now = time
        try {
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDb { db ->
                val library = RoomLibraryRepository(db.books(), now = { now })
                library.add(book)
                db.books().insertOrganization(BookOrganizationEntity(book.id, book.sha256, "COMPLETED", false, null, null, "[]", "[]"))
                val history = RoomBookCompletionRepository(db)
                assertEquals(0, history.observe().first().total)
                assertTrue(library.updateOrganization(book.id, book.sha256, organization(BookReadingStatus.COMPLETED, true)))
                assertEquals(0, history.observe().first().total)
                library.updateOrganization(book.id, book.sha256, organization(BookReadingStatus.READING))
                library.updateOrganization(book.id, book.sha256, organization(BookReadingStatus.COMPLETED))
                library.updateOrganization(book.id, book.sha256, organization(BookReadingStatus.COMPLETED, true))
                val first = history.observe().first()
                assertEquals(1, first.total); assertEquals(time.toInstant().toEpochMilli(), first.entries.single().event.completedAt)
                assertEquals("2026-03-29", first.entries.single().event.localDate)
                assertEquals("Europe/Berlin", first.entries.single().event.zoneId)
                assertFalse(library.updateOrganization(book.id, "b".repeat(64), organization(BookReadingStatus.COMPLETED)))
                assertFalse(library.updateOrganization("missing", book.sha256, organization(BookReadingStatus.COMPLETED)))
                library.updateOrganization(book.id, book.sha256, organization(BookReadingStatus.READING))
                now = time.plusDays(1)
                library.updateOrganization(book.id, book.sha256, organization(BookReadingStatus.COMPLETED))
                assertEquals(2, history.observe().first().total)
                library.updateMetadata(book.id, book.sha256, "Yeni başlık", "Yeni yazar")
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDb { db ->
                val actual = RoomBookCompletionRepository(db).observe().first()
                assertEquals(2, actual.total)
                assertEquals(listOf("2026-03-30", "2026-03-29"), actual.entries.map { it.event.localDate })
                assertTrue(actual.entries.all { it.title == "Yeni başlık" && it.authors == "Yeni yazar" })
                db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun simultaneousCompletionRequestsCreateOnlyOneTransition() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDb { db ->
            val library = RoomLibraryRepository(db.books(), now = { time }); library.add(book)
            coroutineScope { (0 until 8).map { async(Dispatchers.IO) {
                library.updateOrganization(book.id, book.sha256, organization(BookReadingStatus.COMPLETED))
            } }.awaitAll().forEach { assertTrue(it) } }
            assertEquals(1, RoomBookCompletionRepository(db).observe().first().total)
        }
    }
    @Test fun failedEventInsertionRollsBackWholeOrganizationAndCanRetry() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDb { db ->
            val library = RoomLibraryRepository(db.books(), now = { time }); library.add(book)
            val previous = organization(BookReadingStatus.READING, true)
            library.updateOrganization(book.id, book.sha256, previous)
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER refuse_completion BEFORE INSERT ON book_completion_events BEGIN SELECT RAISE(ABORT, 'controlled'); END")
            assertNotNull(runCatching { library.updateOrganization(book.id, book.sha256,
                organization(BookReadingStatus.COMPLETED)) }.exceptionOrNull())
            assertEquals(previous, library.findById(book.id)!!.organization)
            assertEquals(0, db.books().completionEventCount())
            sql.execSQL("DROP TRIGGER refuse_completion")
            assertTrue(library.updateOrganization(book.id, book.sha256, organization(BookReadingStatus.COMPLETED)))
            assertEquals(1, db.books().completionEventCount())
        }
    }
    @Test fun malformedRawValuesAreNotCoercedIntoCompletionDatesAndStaleHashesAreExcluded() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDb { db ->
            val library = RoomLibraryRepository(db.books(), now = { time }); library.add(book)
            library.updateOrganization(book.id, book.sha256, organization(BookReadingStatus.COMPLETED))
            val repo = RoomBookCompletionRepository(db)
            val event = repo.observe().first().entries.single().event
            val sql = db.openHelper.writableDatabase
            for (mutation in listOf("completedAt = 1.5", "completedAt = 'bad'", "completedAt = -1",
                "completedAt = 253402300800000", "localDate = '2026-03-28'", "localDate = 'invalid'",
                "zoneId = 'missing-zone'", "id = 'broken'")) {
                sql.execSQL("UPDATE book_completion_events SET " + mutation)
                assertNotNull(mutation, runCatching { repo.observe().first() }.exceptionOrNull())
                sql.execSQL("UPDATE book_completion_events SET id = ?, completedAt = ?, localDate = ?, zoneId = ?",
                    arrayOf<Any>(event.id, event.completedAt, event.localDate, event.zoneId))
            }
            sql.execSQL("UPDATE book_completion_events SET contentSha256 = ?", arrayOf("b".repeat(64)))
            assertEquals(0, repo.observe().first().total)
            assertEquals(1, db.books().completionEventCount())
        }
    }
    @Test fun newestTwoHundredHaveTrueTotalAndBookDeletionCascadesAllEvents() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDb { db ->
            RoomLibraryRepository(db.books()).add(book)
            for (index in 0 until 205) {
                val at = time.plusMinutes(index.toLong())
                db.books().insertCompletionEvent(BookCompletionEventEntity(UUID.randomUUID().toString(),
                    book.id, book.sha256, at.toInstant().toEpochMilli(), at.toLocalDate().toString(), at.zone.id))
            }
            val actual = RoomBookCompletionRepository(db).observe().first()
            assertEquals(205, actual.total); assertEquals(200, actual.entries.size)
            assertEquals(time.plusMinutes(204).toInstant().toEpochMilli(), actual.entries.first().event.completedAt)
            assertEquals(time.plusMinutes(5).toInstant().toEpochMilli(), actual.entries.last().event.completedAt)
            db.books().deleteExact(book.id, book.sha256)
            assertEquals(0, db.books().completionEventCount())
            assertEquals(0, RoomBookCompletionRepository(db).observe().first().total)
        }
    }
    @Test fun cancellationRecoveryAndClosedDatabaseDoNotProduceAnEmptySuccess() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            RoomLibraryRepository(db.books()).add(book)
            val lock = kotlinx.coroutines.sync.Mutex(locked = true)
            val pending = async { RoomBookCompletionRepository(db, fileMutex = lock).observe().first() }
            yield()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            lock.unlock()
            val blocked = RoomBookCompletionRepository(db, validateAccess = {
                throw BookCompletionException(BookCompletionFailure.RECOVERY)
            })
            val failure = runCatching { blocked.observe().first() }.exceptionOrNull()
            assertEquals(BookCompletionFailure.RECOVERY, (failure as BookCompletionException).reason)
            db.close()
            assertTrue(runCatching { RoomBookCompletionRepository(db).observe().first() }.isFailure)
            assertFalse(db.isOpen)
        } finally { db.close() }
    }

    private fun old(name: String) {
        val schema = JSONObject(requireNotNull(javaClass.classLoader!!.getResourceAsStream(
            "com.yaprak.reader.data.persistence.LibraryDatabase/21.json")).bufferedReader().readText()).getJSONObject("database")
        context.getDatabasePath(name).parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { sql ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val row = entities.getJSONObject(index); val table = row.getString("tableName")
                sql.execSQL(row.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                val indices = row.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) sql.execSQL(indices!!.getJSONObject(j).getString("createSql").replace('$' + "{TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) sql.execSQL(setup.getString(index))
            sql.execSQL("INSERT INTO books VALUES ('book','Kitap','Yazar','EPUB','book.epub','book.epub',100,?,1,NULL)", arrayOf(book.sha256))
            sql.execSQL("INSERT INTO book_organizations VALUES ('book',?,'COMPLETED',1,NULL,NULL,'[]','[]')", arrayOf(book.sha256))
            sql.execSQL("INSERT INTO book_notes VALUES ('note','book',?,'Exact note',8,NULL)", arrayOf(book.sha256))
            sql.execSQL("INSERT INTO reading_progress VALUES ('book',?,1,'saved','one.xhtml',5)", arrayOf(book.sha256))
            sql.version = 21
        }
    }
    @Test fun versionTwentyOneMigratesWithoutFabricatingHistoricalDatesOrLosingNotes() = runBlocking {
        val name = "bm-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            old(name)
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDb { db ->
                    val sql = db.openHelper.writableDatabase
                    assertEquals(26, sql.version)
                    assertEquals(BookReadingStatus.COMPLETED, RoomLibraryRepository(db.books()).findById(book.id)!!.organization.readingStatus)
                    assertEquals("Exact note", sql.compileStatement("SELECT text FROM book_notes").simpleQueryForString())
                    assertEquals("saved", sql.compileStatement("SELECT locatorJson FROM reading_progress").simpleQueryForString())
                    assertEquals(0, db.books().completionEventCount())
                    sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun missingMigrationPreservesOriginalVersionAndUndatedCompletedBook() {
        val name = "bn-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            old(name)
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            try { assertThrows(IllegalStateException::class.java) { db.openHelper.writableDatabase } } finally { db.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
                assertEquals(21, sql.version)
                sql.rawQuery("SELECT readingStatus FROM book_organizations", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("COMPLETED", it.getString(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }
    private suspend fun <T> LibraryDatabase.useDb(block: suspend (LibraryDatabase) -> T): T =
        try { block(this) } finally { close() }
}
