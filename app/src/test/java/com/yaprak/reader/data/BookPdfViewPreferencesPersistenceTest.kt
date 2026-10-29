package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.*
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BookPdfViewPreferencesPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val pdf = Book("pdf", "PDF", "Yazar", BookFormat.PDF, "source.pdf", "pdf.pdf", 100,
        "a".repeat(64), 1, 5)
    private val epub = pdf.copy(id = "epub", format = BookFormat.EPUB, sha256 = "b".repeat(64),
        originalName = "source.epub", storageName = "epub.epub", pageCount = null)

    private suspend fun fixture(callback: RoomDatabase.QueryCallback? = null,
        block: suspend (LibraryDatabase) -> Unit) {
        val builder = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
        if (callback != null) builder.setQueryCallback(callback, Executor { it.run() })
        val database = builder.build()
        try {
            database.books().insert(pdf.toEntity())
            database.books().insert(epub.toEntity())
            block(database)
        } finally { database.close() }
    }

    @Test fun defaultsDoNotWrite() = runBlocking {
        fixture { database ->
            val repository = RoomBookPdfViewPreferencesRepository(database)
            assertEquals(BookPdfViewPreferences(), repository.observe(pdf.id, pdf.sha256).first())
            assertTrue(database.bookPdfViewPreferences().all().isEmpty())
            repository.saveCrop(pdf.id, pdf.sha256, 0, 0)
            repository.saveFit(pdf.id, pdf.sha256, PdfFitPreference.PAGE)
            repository.saveSpread(pdf.id, pdf.sha256, false)
            assertTrue(database.bookPdfViewPreferences().all().isEmpty())
            assertEquals(pdf.toEntity(), database.books().findById(pdf.id))
            assertNull(database.reading().resume(pdf.id, pdf.sha256))
            assertTrue(database.reading().observed(pdf.id, pdf.sha256).isEmpty())
        }
    }

    @Test fun fieldSavesMergeLatest() = runBlocking {
        fixture { database ->
            val mutex = Mutex()
            val first = RoomBookPdfViewPreferencesRepository(database, mutex)
            val second = RoomBookPdfViewPreferencesRepository(database, mutex)
            coroutineScope {
                listOf(
                    async(Dispatchers.Default) { first.saveCrop(pdf.id, pdf.sha256, 12, 7) },
                    async(Dispatchers.Default) { second.saveFit(pdf.id, pdf.sha256, PdfFitPreference.WIDTH) },
                    async(Dispatchers.Default) { second.saveSpread(pdf.id, pdf.sha256, true) },
                ).awaitAll()
            }
            val expected = BookPdfViewPreferences(12, 7, PdfFitPreference.WIDTH, true)
            assertEquals(expected, first.observe(pdf.id, pdf.sha256).first())
            first.saveCrop(pdf.id, pdf.sha256, 0, 0)
            assertEquals(expected.copy(horizontalPercent = 0, verticalPercent = 0), second.observe(pdf.id, pdf.sha256).first())
            first.saveSpread(pdf.id, pdf.sha256, false)
            assertEquals(PdfFitPreference.WIDTH, second.observe(pdf.id, pdf.sha256).first().fitMode)
            assertEquals(1, database.bookPdfViewPreferences().all().size)
        }
    }

    @Test fun reopenRetainsOnlyMatchingBook() = runBlocking {
        val name = "pv-${UUID.randomUUID().toString().take(8)}.db"
        var database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            val other = pdf.copy(id = "other", sha256 = "c".repeat(64), storageName = "other.pdf")
            database.books().insert(pdf.toEntity()); database.books().insert(other.toEntity())
            val repository = RoomBookPdfViewPreferencesRepository(database)
            repository.saveCrop(pdf.id, pdf.sha256, 20, 10)
            repository.saveFit(pdf.id, pdf.sha256, PdfFitPreference.WIDTH)
            repository.saveSpread(pdf.id, pdf.sha256, true)
            repository.saveCrop(other.id, other.sha256, 3, 4)
            assertNotNull(runCatching { database.books().insert(pdf.copy(id = "duplicate").toEntity()) }.exceptionOrNull())
            database.close()
            database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            val reopened = RoomBookPdfViewPreferencesRepository(database)
            assertEquals(BookPdfViewPreferences(20, 10, PdfFitPreference.WIDTH, true), reopened.observe(pdf.id, pdf.sha256).first())
            assertEquals(BookPdfViewPreferences(3, 4), reopened.observe(other.id, other.sha256).first())
            assertEquals(2, database.bookPdfViewPreferences().all().size)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun scopeProofAndCascade() = runBlocking {
        fixture { database ->
            val repository = RoomBookPdfViewPreferencesRepository(database)
            repository.saveCrop(pdf.id, pdf.sha256, 6, 8)
            val other = pdf.copy(id = "other", sha256 = "c".repeat(64), storageName = "other.pdf")
            database.books().insert(other.toEntity()); repository.saveSpread(other.id, other.sha256, true)
            for ((id, hash) in listOf("missing" to pdf.sha256, epub.id to epub.sha256,
                pdf.id to "d".repeat(64))) {
                assertFailure(BookPdfViewPreferencesFailure.STALE_BOOK) { repository.observe(id, hash).first() }
                assertFailure(BookPdfViewPreferencesFailure.STALE_BOOK) { repository.saveFit(id, hash, PdfFitPreference.WIDTH) }
            }
            database.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ? WHERE id = ?", arrayOf("e".repeat(64), pdf.id))
            assertFailure(BookPdfViewPreferencesFailure.STALE_BOOK) { repository.observe(pdf.id, pdf.sha256).first() }
            val stale = database.bookPdfViewPreferences().all().single { it.bookId == pdf.id }
            assertFailure(BookPdfViewPreferencesFailure.STALE_BOOK) { repository.observe(pdf.id, "e".repeat(64)).first() }
            assertFailure(BookPdfViewPreferencesFailure.STALE_BOOK) { repository.saveCrop(pdf.id, "e".repeat(64), 0, 0) }
            assertFailure(BookPdfViewPreferencesFailure.STALE_BOOK) { repository.saveFit(pdf.id, "e".repeat(64), PdfFitPreference.PAGE) }
            assertFailure(BookPdfViewPreferencesFailure.STALE_BOOK) { repository.saveSpread(pdf.id, "e".repeat(64), false) }
            assertEquals(stale, database.bookPdfViewPreferences().all().single { it.bookId == pdf.id })
            database.openHelper.writableDatabase.execSQL("UPDATE books SET format = 'EPUB' WHERE id = ?", arrayOf(pdf.id))
            assertFailure(BookPdfViewPreferencesFailure.STALE_BOOK) { repository.observe(pdf.id, "e".repeat(64)).first() }
            database.books().deleteExact(pdf.id, "e".repeat(64))
            assertFailure(BookPdfViewPreferencesFailure.STALE_BOOK) { repository.observe(pdf.id, "e".repeat(64)).first() }
            assertEquals(other.id, database.bookPdfViewPreferences().all().single().bookId)
            assertTrue(repository.observe(other.id, other.sha256).first().doublePagePreferred)
        }
    }

    @Test fun invalidInputDoesNotOverwrite() = runBlocking {
        fixture { database ->
            val repository = RoomBookPdfViewPreferencesRepository(database)
            repository.saveCrop(pdf.id, pdf.sha256, 8, 9)
            val saved = database.bookPdfViewPreferences().all().single()
            for ((horizontal, vertical) in listOf(-1 to 0, 0 to -1, 21 to 0, 0 to 21, Int.MAX_VALUE to 0)) {
                assertFailure(BookPdfViewPreferencesFailure.INVALID_VALUE) { repository.saveCrop(pdf.id, pdf.sha256, horizontal, vertical) }
                assertEquals(saved, database.bookPdfViewPreferences().all().single())
            }
            for ((id, hash) in listOf("" to pdf.sha256, pdf.id to "bad", "x".repeat(201) to pdf.sha256,
                "bad\u0000id" to pdf.sha256, "bad\nid" to pdf.sha256,
                "bad\uD800id" to pdf.sha256, "bad\uDC00id" to pdf.sha256,
                "bad\uD83D\uDE00id" to pdf.sha256)) {
                assertFailure(BookPdfViewPreferencesFailure.INVALID_VALUE) { repository.observe(id, hash).first() }
                assertFailure(BookPdfViewPreferencesFailure.INVALID_VALUE) { repository.saveSpread(id, hash, true) }
            }
        }
    }

    @Test fun malformedSqlValuesAreRejected() = runBlocking {
        fixture { database ->
            val repository = RoomBookPdfViewPreferencesRepository(database)
            val original = BookPdfViewPreferencesEntity(pdf.id, pdf.sha256, 4, 5, "WIDTH", 1)
            val mutations = listOf<Pair<String, Any>>(
                "horizontalPercent" to 1.5, "horizontalPercent" to "broken", "horizontalPercent" to 21,
                "horizontalPercent" to 4294967296L,
                "verticalPercent" to 1.5, "verticalPercent" to -1, "verticalPercent" to 4294967316L,
                "doublePagePreferred" to 1.5, "doublePagePreferred" to "broken", "doublePagePreferred" to 2,
                "doublePagePreferred" to -1, "doublePagePreferred" to 4294967297L,
                "fitMode" to "BROKEN", "contentSha256" to "bad",
            )
            for ((field, value) in mutations) {
                database.bookPdfViewPreferences().save(original)
                database.openHelper.writableDatabase.execSQL("UPDATE book_pdf_view_preferences SET $field = ? WHERE bookId = ?", arrayOf(value, pdf.id))
                val before = sqlRows(database, "book_pdf_view_preferences")
                assertFailure(BookPdfViewPreferencesFailure.SOURCE) { repository.observe(pdf.id, pdf.sha256).first() }
                assertFailure(BookPdfViewPreferencesFailure.SOURCE) { repository.saveCrop(pdf.id, pdf.sha256, 0, 0) }
                assertFailure(BookPdfViewPreferencesFailure.SOURCE) { repository.saveFit(pdf.id, pdf.sha256, PdfFitPreference.PAGE) }
                assertFailure(BookPdfViewPreferencesFailure.SOURCE) { repository.saveSpread(pdf.id, pdf.sha256, false) }
                assertEquals(before, sqlRows(database, "book_pdf_view_preferences"))
            }
        }
    }

    @Test fun invalidationsExposeDurableChanges() = runBlocking {
        fixture { database ->
            val repository = RoomBookPdfViewPreferencesRepository(database)
            val changes = Channel<BookPdfViewPreferences>(Channel.UNLIMITED)
            val scopeFailure = CompletableDeferred<BookPdfViewPreferencesFailure>()
            val collector = launch(Dispatchers.Default) {
                try { repository.observe(pdf.id, pdf.sha256).collect { changes.send(it) } }
                catch (failure: BookPdfViewPreferencesException) { scopeFailure.complete(failure.reason) }
            }
            suspend fun await(predicate: (BookPdfViewPreferences) -> Boolean) = withTimeout(5000) {
                while (true) { val next = changes.receive(); if (predicate(next)) return@withTimeout next }
                error("unreachable")
            }
            try {
                await { it == BookPdfViewPreferences() }
                repository.saveFit(pdf.id, pdf.sha256, PdfFitPreference.WIDTH)
                await { it.fitMode == PdfFitPreference.WIDTH }
                repository.saveCrop(pdf.id, pdf.sha256, 3, 2)
                await { it.horizontalPercent == 3 && it.verticalPercent == 2 && it.fitMode == PdfFitPreference.WIDTH }
                repository.saveSpread(pdf.id, pdf.sha256, true)
                await { it.doublePagePreferred }
                database.books().deleteExact(pdf.id, pdf.sha256)
                assertEquals(BookPdfViewPreferencesFailure.STALE_BOOK, withTimeout(5000) { scopeFailure.await() })
            } finally { collector.cancelAndJoin(); changes.close() }
        }
    }

    @Test fun guardRunsAfterSharedMutex() = runBlocking {
        fixture { database ->
            val mutex = Mutex(locked = true)
            val ready = AtomicBoolean(true)
            val guardCalls = java.util.concurrent.atomic.AtomicInteger()
            val repository = RoomBookPdfViewPreferencesRepository(database, mutex, validateAccess = {
                guardCalls.incrementAndGet(); check(ready.get()) { "Recovery is pending" }
            })
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                assertFailure(BookPdfViewPreferencesFailure.SOURCE) { repository.saveSpread(pdf.id, pdf.sha256, true) }
            }
            assertEquals(0, guardCalls.get())
            ready.set(false); mutex.unlock(); pending.await()
            assertTrue(database.bookPdfViewPreferences().all().isEmpty())
            ready.set(true); repository.saveSpread(pdf.id, pdf.sha256, true)
            assertTrue(repository.observe(pdf.id, pdf.sha256).first().doublePagePreferred)
        }
    }

    @Test fun cancelledQueuedWriteDoesNotCommit() = runBlocking {
        fixture { database ->
            val mutex = Mutex(locked = true)
            val repository = RoomBookPdfViewPreferencesRepository(database, mutex)
            val writing = launch(start = CoroutineStart.UNDISPATCHED) { repository.saveSpread(pdf.id, pdf.sha256, true) }
            writing.cancelAndJoin(); mutex.unlock()
            assertTrue(writing.isCancelled)
            assertTrue(database.bookPdfViewPreferences().all().isEmpty())
        }
    }

    @Test fun nativeAbortRetainsSavedFields() = runBlocking {
        fixture { database ->
            val repository = RoomBookPdfViewPreferencesRepository(database)
            repository.saveCrop(pdf.id, pdf.sha256, 4, 5)
            val before = database.bookPdfViewPreferences().all().single()
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_pdf_view BEFORE INSERT ON book_pdf_view_preferences BEGIN SELECT RAISE(ABORT, 'controlled failure'); END")
            assertFailure(BookPdfViewPreferencesFailure.SOURCE) { repository.saveFit(pdf.id, pdf.sha256, PdfFitPreference.WIDTH) }
            assertEquals(before, database.bookPdfViewPreferences().all().single())
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_pdf_view")
            repository.saveFit(pdf.id, pdf.sha256, PdfFitPreference.WIDTH)
            assertEquals(BookPdfViewPreferences(4, 5, PdfFitPreference.WIDTH), repository.observe(pdf.id, pdf.sha256).first())
        }
    }

    @Test fun originalCallerCancellationRollsBack() = runBlocking {
        val running = AtomicReference<Job?>()
        val cancel = AtomicBoolean(false)
        val callback = object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                if (cancel.get() && sqlQuery.trimStart().startsWith("INSERT") && sqlQuery.contains("book_pdf_view_preferences"))
                    running.get()?.cancel()
            }
        }
        fixture(callback) { database ->
            val repository = RoomBookPdfViewPreferencesRepository(database)
            repository.saveCrop(pdf.id, pdf.sha256, 4, 5)
            val before = database.bookPdfViewPreferences().all().single()
            val writing = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                repository.saveFit(pdf.id, pdf.sha256, PdfFitPreference.WIDTH)
            }
            running.set(writing); cancel.set(true); writing.start(); withTimeout(5000) { writing.join() }
            assertTrue(writing.isCancelled)
            cancel.set(false)
            assertEquals(before, database.bookPdfViewPreferences().all().single())
            repository.saveFit(pdf.id, pdf.sha256, PdfFitPreference.WIDTH)
            assertEquals(BookPdfViewPreferences(4, 5, PdfFitPreference.WIDTH), repository.observe(pdf.id, pdf.sha256).first())
        }
    }

    @Test fun initialLazySourceCanOpen() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            assertFalse(database.isOpen)
            val repository = RoomBookPdfViewPreferencesRepository(database)
            assertFailure(BookPdfViewPreferencesFailure.STALE_BOOK) { repository.observe(pdf.id, pdf.sha256).first() }
            assertTrue(database.isOpen)
            database.books().insert(pdf.toEntity())
            assertEquals(BookPdfViewPreferences(), repository.observe(pdf.id, pdf.sha256).first())
            assertTrue(database.bookPdfViewPreferences().all().isEmpty())
        } finally { database.close() }
    }

    @Test fun closedSourceDoesNotReopen() = runBlocking {
        val name = "pc-${UUID.randomUUID().toString().take(8)}.db"
        var database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            database.books().insert(pdf.toEntity())
            val repository = RoomBookPdfViewPreferencesRepository(database)
            repository.saveSpread(pdf.id, pdf.sha256, true)
            database.close()
            assertFailure(BookPdfViewPreferencesFailure.SOURCE) { repository.saveSpread(pdf.id, pdf.sha256, false) }
            assertFalse(database.isOpen)
            assertFailure(BookPdfViewPreferencesFailure.SOURCE) { repository.observe(pdf.id, pdf.sha256).first() }
            assertFalse(database.isOpen)
            database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            assertTrue(RoomBookPdfViewPreferencesRepository(database).observe(pdf.id, pdf.sha256).first().doublePagePreferred)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun room16MigrationKeepsEveryTable() = runBlocking {
        val name = "pm-${UUID.randomUUID().toString().take(8)}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/16.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val before = linkedMapOf<String, Pair<String, List<List<String?>>>>()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    for (j in 0 until (indices?.length() ?: 0))
                        sqlite.execSQL(indices!!.getJSONObject(j).getString("createSql").replace('$' + "{TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) sqlite.execSQL(setup.getString(index))
                sqlite.execSQL("INSERT INTO books VALUES ('epub','EPUB','Yazar','EPUB','source.epub','epub.epub',100,?,1,NULL)", arrayOf(epub.sha256))
                sqlite.execSQL("INSERT INTO books VALUES ('pdf','PDF','Yazar','PDF','source.pdf','pdf.pdf',100,?,1,5)", arrayOf(pdf.sha256))
                sqlite.execSQL("INSERT INTO reading_progress VALUES ('epub',?,1,'saved','chapter',5)", arrayOf(epub.sha256))
                sqlite.execSQL("INSERT INTO observed_text VALUES ('epub',?,1,'chapter','0/0',0,4,?)", arrayOf(epub.sha256, "c".repeat(64)))
                sqlite.execSQL("INSERT INTO bookmarks VALUES ('mark','epub',?,'key','Mark','saved','chapter',1,6)", arrayOf(epub.sha256))
                sqlite.execSQL("INSERT INTO book_epub_theme_overrides VALUES ('epub',?,'SEPIA')", arrayOf(epub.sha256))
                sqlite.execSQL("INSERT INTO book_epub_font_size_overrides VALUES ('epub',?,125)", arrayOf(epub.sha256))
                sqlite.execSQL("INSERT INTO book_epub_font_overrides VALUES ('epub',?,'SERIF')", arrayOf(epub.sha256))
                sqlite.execSQL("INSERT INTO book_epub_margin_overrides VALUES ('epub',?,150)", arrayOf(epub.sha256))
                sqlite.execSQL("INSERT INTO book_epub_line_spacing_overrides VALUES ('epub',?,NULL)", arrayOf(epub.sha256))
                sqlite.execSQL("INSERT INTO saved_words VALUES ('word','epub',?,'leaf','yaprak',7)", arrayOf(epub.sha256))
                sqlite.execSQL("INSERT INTO book_notes VALUES ('note','pdf',?,'Eski sayfa notu',9,3)", arrayOf(pdf.sha256))
                sqlite.execSQL("INSERT INTO epub_passages VALUES ('passage','epub',?,1,'OPS/chapter.xhtml',?,0,4,'leaf',10,'GREEN','Exact note',7)", arrayOf(epub.sha256, "c".repeat(64)))
                sqlite.execSQL("INSERT INTO book_organizations VALUES ('epub',?,'READING',1,'Seri','Tür','[\"Etiket\"]','[\"Raf\"]')", arrayOf(epub.sha256))
                sqlite.execSQL("INSERT INTO book_covers VALUES ('epub',?,'cover','cover.png','image/png',20,?,8,12)", arrayOf(epub.sha256, "d".repeat(64)))
                sqlite.execSQL("INSERT INTO reading_duration_receipts VALUES (?,?,?,?,?,?,?,?)", arrayOf<Any?>(UUID.randomUUID().toString(),
                    pdf.id, pdf.sha256, "2026-10-01", "Europe/Istanbul", 1790848800000L, 1790848805000L, 5000L))
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val fields = entity.getJSONArray("fields"); val table = entity.getString("tableName")
                    val columns = (0 until fields.length()).joinToString(",") { fields.getJSONObject(it).getString("columnName") }
                    before[table] = columns to rows(sqlite.rawQuery("SELECT $columns FROM $table", null))
                }
                sqlite.version = 16
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { database ->
                    before.forEach { (table, snapshot) ->
                        assertEquals(table, snapshot.second, rows(database.openHelper.writableDatabase.query("SELECT ${snapshot.first} FROM $table")))
                    }
                    assertTrue(database.bookPdfViewPreferences().all().isEmpty())
                    val repository = RoomBookPdfViewPreferencesRepository(database)
                    assertEquals(BookPdfViewPreferences(), repository.observe(pdf.id, pdf.sha256).first())
                    repository.saveFit(pdf.id, pdf.sha256, PdfFitPreference.WIDTH)
                }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { database ->
                assertEquals(PdfFitPreference.WIDTH, RoomBookPdfViewPreferencesRepository(database).observe(pdf.id, pdf.sha256).first().fitMode)
                assertEquals(26, database.openHelper.writableDatabase.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); it.getInt(0) })
                database.books().deleteExact(pdf.id, pdf.sha256)
                assertTrue(database.bookPdfViewPreferences().all().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun sqlRows(database: LibraryDatabase, table: String) = rows(database.openHelper.writableDatabase.query("SELECT * FROM $table"))
    private fun rows(cursor: Cursor): List<List<String?>> = cursor.use { buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
    } }
    private suspend fun assertFailure(reason: BookPdfViewPreferencesFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (failure: BookPdfViewPreferencesException) { assertEquals(reason, failure.reason) }
    }
    private suspend fun <T> LibraryDatabase.useDatabase(block: suspend (LibraryDatabase) -> T): T = try { block(this) } finally { close() }
}
