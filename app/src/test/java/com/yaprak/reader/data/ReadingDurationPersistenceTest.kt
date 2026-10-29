package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.statistics.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReadingDurationPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("book", "Kitap", "Yazar", BookFormat.EPUB, "source.epub", "book.epub", 100, "a".repeat(64), 1)
    private val start = Instant.parse("2026-10-01T10:00:00Z").toEpochMilli()
    private fun receipt(book: Book = this.book, elapsed: Long = 5000, id: String = UUID.randomUUID().toString()) =
        ReadingDurationReceipt(id, book.id, book.sha256, "2026-10-01", "Europe/Istanbul", start, start + elapsed, elapsed)

    private suspend fun fixture(callback: RoomDatabase.QueryCallback? = null, block: suspend (LibraryDatabase) -> Unit) {
        val builder = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
        if (callback != null) builder.setQueryCallback(callback, Executor { it.run() })
        val db = builder.build()
        try { db.books().insert(book.toEntity()); block(db) } finally { db.close() }
    }

    @Test fun `cumulative retries lower checkpoints and out of order concurrent writes retain one MAX receipt`() = runBlocking {
        fixture { db ->
            val repo = RoomReadingDurationRepository(db)
            val first = receipt()
            repo.record(first); repo.record(first)
            repo.record(first.copy(elapsedMillis = 3000, endedAt = start + 9000))
            assertEquals(first, db.readingDurations().all().single().toReceipt())
            coroutineScope {
                listOf(6000L, 9000L, 7000L).map { elapsed -> async(Dispatchers.Default) {
                    repo.record(first.copy(elapsedMillis = elapsed, endedAt = start + elapsed))
                } }.awaitAll()
            }
            repo.record(first.copy(elapsedMillis = 9000, endedAt = start + 10_000))
            repo.record(first.copy(elapsedMillis = 8000, endedAt = start + 20_000))
            val stored = db.readingDurations().all().single().toReceipt()
            assertEquals(9000L, stored.elapsedMillis); assertEquals(start + 10_000, stored.endedAt)
            assertEquals(9000L, repo.observe().first().totalMillis)
        }
    }

    @Test fun `same receipt id cannot retarget book source day zone or original start`() = runBlocking {
        fixture { db ->
            val other = book.copy(id = "other", sha256 = "b".repeat(64))
            db.books().insert(other.toEntity())
            val repo = RoomReadingDurationRepository(db); val first = receipt(); repo.record(first)
            for (changed in listOf(first.copy(bookId = other.id, contentSha256 = other.sha256),
                first.copy(zoneId = "UTC"), first.copy(startedAt = start + 1),
                first.copy(localDate = "2026-10-02", startedAt = start + 86_400_000, endedAt = start + 86_405_000))) {
                assertFailure(ReadingDurationFailure.INVALID_RECORD) { repo.record(changed) }
                assertEquals(first, db.readingDurations().all().single().toReceipt())
            }
            assertFailure(ReadingDurationFailure.STALE_BOOK) { repo.record(first.copy(contentSha256 = "c".repeat(64))) }
        }
    }

    @Test fun `empty books current metadata and Long day totals are durable and no progress becomes time`() = runBlocking {
        fixture { db ->
            val other = book.copy(id = "empty", sha256 = "b".repeat(64), format = BookFormat.PDF, pageCount = 5)
            db.books().insert(other.toEntity())
            db.reading().save(ReadingProgressEntity(book.id, book.sha256, 1, "{}", "chapter", 123))
            val library = RoomLibraryRepository(db.books()); val organization = BookOrganization(favorite = true, series = "Seri")
            library.updateOrganization(book.id, book.sha256, organization)
            val repo = RoomReadingDurationRepository(db)
            val empty = repo.observe().first()
            assertEquals(0L, empty.totalMillis); assertTrue(empty.days.isEmpty()); assertEquals(2, empty.books.size)
            repeat(20) { repo.record(receipt(elapsed = MAX_READING_DURATION_MILLIS).copy(endedAt = start + 1000)) }
            val snapshot = repo.observe().first()
            assertEquals(20L * MAX_READING_DURATION_MILLIS, snapshot.totalMillis)
            assertEquals(snapshot.totalMillis, snapshot.days.single().elapsedMillis)
            val current = snapshot.books.single { it.book.id == book.id }
            assertEquals(organization, current.book.organization); assertEquals(123L, current.book.lastReadAt)
            assertEquals(snapshot.totalMillis, current.totalMillis)
            assertEquals(0L, snapshot.books.single { it.book.id == other.id }.totalMillis)
        }
    }

    @Test fun `live metadata and duration invalidations expose current full books without restarting the flow`() = runBlocking {
        fixture { db ->
            val repo = RoomReadingDurationRepository(db); val library = RoomLibraryRepository(db.books())
            val updates = Channel<ReadingDurationSnapshot>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { repo.observe().collect { updates.send(it) } }
            suspend fun await(predicate: (ReadingDurationSnapshot) -> Boolean): ReadingDurationSnapshot = withTimeout(5000) {
                while (true) { val next = updates.receive(); if (predicate(next)) return@withTimeout next }
                error("unreachable")
            }
            try {
                await { it.books.size == 1 }
                library.updateMetadata(book.id, book.sha256, "Changed", "Author")
                await { it.books.single().book.title == "Changed" }
                library.updateOrganization(book.id, book.sha256, BookOrganization(favorite = true))
                await { it.books.single().book.organization.favorite }
                repo.record(receipt()); await { it.totalMillis == 5000L }
                db.reading().save(ReadingProgressEntity(book.id, book.sha256, 1, "{}", "chapter", 456))
                await { it.books.single().book.lastReadAt == 456L }
            } finally { collector.cancelAndJoin(); updates.close() }
        }
    }

    @Test fun `changed hash excludes stale receipts and deletion cascades only its book`() = runBlocking {
        fixture { db ->
            val repo = RoomReadingDurationRepository(db); val old = receipt(); repo.record(old)
            val other = book.copy(id = "other", sha256 = "b".repeat(64)); db.books().insert(other.toEntity())
            repo.record(receipt(other, elapsed = 7000))
            db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ? WHERE id = ?", arrayOf("c".repeat(64), book.id))
            assertEquals(7000L, repo.observe().first().totalMillis)
            assertFailure(ReadingDurationFailure.STALE_BOOK) { repo.record(old) }
            db.books().deleteExact(book.id, "c".repeat(64))
            assertEquals(1, db.readingDurations().all().size)
            assertEquals(other.id, repo.observe().first().books.single().book.id)
        }
    }

    @Test fun `strict SQL types and receipt semantics reject corrupt rows before aggregates or updates`() = runBlocking {
        fixture { db ->
            val repo = RoomReadingDurationRepository(db); val first = receipt(); repo.record(first)
            val mutations = listOf<Pair<String, Any>>("elapsedMillis" to 1.5, "startedAt" to 1.5,
                "endedAt" to -1L, "elapsedMillis" to 0L, "elapsedMillis" to MAX_READING_DURATION_MILLIS + 1,
                "localDate" to "2026-02-30", "zoneId" to "Unknown/Zone", "contentSha256" to "bad", "id" to "bad")
            for ((field, value) in mutations) {
                db.openHelper.writableDatabase.execSQL("UPDATE reading_duration_receipts SET $field = ?", arrayOf(value))
                assertFailure(ReadingDurationFailure.SOURCE) { repo.observe().first() }
                if (field != "id") assertFailure(ReadingDurationFailure.SOURCE) { repo.record(first) }
                db.openHelper.writableDatabase.execSQL("DELETE FROM reading_duration_receipts")
                repo.record(first)
            }
        }
    }

    @Test fun `original caller cancellation at native update rolls back its checkpoint`() = runBlocking {
        var running: Job? = null; var cancel = false; val updates = AtomicInteger()
        val callback = object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                if (cancel && sqlQuery.startsWith("UPDATE reading_duration_receipts")) {
                    updates.incrementAndGet(); running!!.cancel(CancellationException("controlled checkpoint cancellation"))
                }
            }
        }
        fixture(callback) { db ->
            val repo = RoomReadingDurationRepository(db); val first = receipt(); repo.record(first); cancel = true
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { repo.record(first.copy(elapsedMillis = 9000, endedAt = start + 9000)) }
            running = job; job.start(); withTimeout(5000) { job.join() }; cancel = false
            assertTrue(job.isCancelled); assertEquals(1, updates.get())
            assertEquals(first, db.readingDurations().all().single().toReceipt())
        }
    }

    @Test fun `native SQL abort preserves earlier receipt and a retry can advance it`() = runBlocking {
        fixture { db ->
            val repo = RoomReadingDurationRepository(db); val first = receipt(); repo.record(first)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_duration BEFORE UPDATE ON reading_duration_receipts BEGIN SELECT RAISE(ABORT, 'controlled failure'); END")
            val later = first.copy(elapsedMillis = 9000, endedAt = start + 9000)
            assertFailure(ReadingDurationFailure.SOURCE) { repo.record(later) }
            assertEquals(first, db.readingDurations().all().single().toReceipt())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_duration")
            repo.record(later); assertEquals(later, db.readingDurations().all().single().toReceipt())
        }
    }

    @Test fun `queued cancellation skips access guard and recovery guard touches no database`() = runBlocking {
        fixture { db ->
            val mutex = Mutex(locked = true); val guards = AtomicInteger()
            val repo = RoomReadingDurationRepository(db, mutex, { guards.incrementAndGet() })
            val queued = launch(Dispatchers.Default) { repo.record(receipt()) }
            yield(); queued.cancelAndJoin(); mutex.unlock()
            assertEquals(0, guards.get()); assertTrue(db.readingDurations().all().isEmpty())
            val denied = RoomReadingDurationRepository(db, validateAccess = { throw ReadingDurationException(ReadingDurationFailure.RECOVERY) })
            assertFailure(ReadingDurationFailure.RECOVERY) { denied.record(receipt()) }
            assertFailure(ReadingDurationFailure.RECOVERY) { denied.observe().first() }
            assertTrue(db.readingDurations().all().isEmpty())
        }
    }

    @Test fun `closed Room fails explicitly without reopening and receipts survive real reopen`() = runBlocking {
        val name = "duration-${UUID.randomUUID().toString().take(8)}.db"
        var db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            db.books().insert(book.toEntity()); val first = receipt(); val old = RoomReadingDurationRepository(db); old.record(first)
            db.close()
            assertFailure(ReadingDurationFailure.SOURCE) { old.record(first) }
            assertFailure(ReadingDurationFailure.SOURCE) { old.observe().first() }
            assertFalse(db.isOpen)
            db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            assertEquals(5000L, RoomReadingDurationRepository(db).observe().first().totalMillis)
            assertEquals(first, db.readingDurations().all().single().toReceipt())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun `receipt cap rejects a new identity but still permits idempotent checkpoints`() = runBlocking {
        fixture { db ->
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("""WITH RECURSIVE n(value) AS
                (SELECT 1 UNION ALL SELECT value + 1 FROM n WHERE value < $MAX_READING_DURATION_RECEIPTS)
                INSERT INTO reading_duration_receipts SELECT printf('%08x-0000-4000-8000-000000000000', value),
                'book', ?, '2026-10-01', 'Europe/Istanbul', ?, ?, 1000 FROM n""".trimIndent(), arrayOf<Any>(book.sha256, start, start + 1000)) }
            val repo = RoomReadingDurationRepository(db)
            assertFailure(ReadingDurationFailure.LIMIT) { repo.record(receipt()) }
            repo.record(receipt(id = "00000001-0000-4000-8000-000000000000"))
            assertEquals(MAX_READING_DURATION_RECEIPTS.toLong(), withContext(Dispatchers.IO) {
                db.openHelper.writableDatabase.compileStatement("SELECT COUNT(*) FROM reading_duration_receipts").simpleQueryForLong()
            })
        }
    }

    // Robolectric includes the method name in its temporary path. Keep room for native SQLite journal files on Windows.
    @Test fun `Room15 migration preserves every table`() = runBlocking {
        val name = "dm-${UUID.randomUUID().toString().take(8)}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/15.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val before = linkedMapOf<String, Pair<String, List<List<String?>>>>()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    for (j in 0 until (indices?.length() ?: 0)) sqlite.execSQL(indices!!.getJSONObject(j).getString("createSql").replace('$' + "{TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) sqlite.execSQL(setup.getString(index))
                sqlite.execSQL("INSERT INTO books VALUES ('book','Kitap','Yazar','EPUB','source.epub','book.epub',100,?,1,NULL)", arrayOf(book.sha256))
                sqlite.execSQL("INSERT INTO reading_progress VALUES ('book',?,1,'saved','chapter',5)", arrayOf(book.sha256))
                sqlite.execSQL("INSERT INTO observed_text VALUES ('book',?,1,'chapter','0/0',0,4,?)", arrayOf(book.sha256, "c".repeat(64)))
                sqlite.execSQL("INSERT INTO bookmarks VALUES ('mark','book',?,'key','Mark','saved','chapter',1,6)", arrayOf(book.sha256))
                sqlite.execSQL("INSERT INTO book_epub_theme_overrides VALUES ('book',?,'SEPIA')", arrayOf(book.sha256))
                sqlite.execSQL("INSERT INTO book_epub_font_size_overrides VALUES ('book',?,125)", arrayOf(book.sha256))
                sqlite.execSQL("INSERT INTO book_epub_font_overrides VALUES ('book',?,'SERIF')", arrayOf(book.sha256))
                sqlite.execSQL("INSERT INTO book_epub_margin_overrides VALUES ('book',?,150)", arrayOf(book.sha256))
                sqlite.execSQL("INSERT INTO book_epub_line_spacing_overrides VALUES ('book',?,140)", arrayOf(book.sha256))
                sqlite.execSQL("INSERT INTO saved_words VALUES ('word','book',?,'leaf','yaprak',7)", arrayOf(book.sha256))
                sqlite.execSQL("INSERT INTO book_notes VALUES ('note','book',?,'Eski not',9,NULL)", arrayOf(book.sha256))
                sqlite.execSQL("INSERT INTO epub_passages VALUES ('passage','book',?,1,'OPS/chapter.xhtml',?,0,4,'leaf',10,'GREEN','Exact note',7)", arrayOf(book.sha256, "c".repeat(64)))
                sqlite.execSQL("INSERT INTO book_organizations VALUES ('book',?,'READING',1,'Seri','Tür','[\"Etiket\"]','[\"Raf\"]')", arrayOf(book.sha256))
                sqlite.execSQL("INSERT INTO book_covers VALUES ('book',?,'cover','cover.png','image/png',20,?,8,12)", arrayOf(book.sha256, "d".repeat(64)))
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val fields = entity.getJSONArray("fields"); val table = entity.getString("tableName")
                    val columns = (0 until fields.length()).joinToString(",") { fields.getJSONObject(it).getString("columnName") }
                    before[table] = columns to rows(sqlite.rawQuery("SELECT $columns FROM $table", null))
                }
                sqlite.version = 15
            }
            dbOpen(name, migrate = true).useDatabase { db ->
                before.forEach { (table, snapshot) -> assertEquals(table, snapshot.second, rows(db.openHelper.writableDatabase.query("SELECT ${snapshot.first} FROM $table"))) }
                assertTrue(db.readingDurations().all().isEmpty())
                assertEquals(0L, RoomReadingDurationRepository(db).observe().first().totalMillis)
                RoomReadingDurationRepository(db).record(receipt())
            }
            dbOpen(name).useDatabase { db ->
                assertEquals(5000L, RoomReadingDurationRepository(db).observe().first().totalMillis)
                assertEquals(26, db.openHelper.writableDatabase.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); it.getInt(0) })
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun dbOpen(name: String, migrate: Boolean = false): LibraryDatabase = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
        .apply { if (migrate) addMigrations(LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26) }.build()
    private fun rows(cursor: Cursor): List<List<String?>> = cursor.use { buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
    } }
    private suspend fun assertFailure(reason: ReadingDurationFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") } catch (failure: ReadingDurationException) { assertEquals(reason, failure.reason) }
    }
    private suspend fun <T> LibraryDatabase.useDatabase(block: suspend (LibraryDatabase) -> T): T = try { block(this) } finally { close() }
}
