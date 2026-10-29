package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomEpubPassageRepository
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.passages.EpubAnnotationLimitException
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.domain.passages.EpubPassageChangedException
import com.yaprak.reader.domain.passages.MAX_EPUB_ANNOTATIONS_PER_BOOK
import com.yaprak.reader.reader.SourceTextAnchor
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EpubAnnotationPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("book", "Kitap", "Yazar", BookFormat.EPUB,
        "source.epub", "book.epub", 100, "a".repeat(64), 1)
    private val selected = "  İğne\n🌿  "
    private val anchor = SourceTextAnchor("OPS/chapter.xhtml", 2, 2 + selected.length, "b".repeat(64))
    private val note = "  İlk satır\nİkinci satır — 漢字 🌿\t  "

    @Test fun `v14 migration preserves every old column and plain passage across reopen`() = runBlocking {
        val name = "am-${UUID.randomUUID().toString().take(8)}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream(
            "com.yaprak.reader.data.persistence.LibraryDatabase/14.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val before = linkedMapOf<String, Pair<String, List<List<String?>>>>()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    for (j in 0 until (indices?.length() ?: 0)) sqlite.execSQL(indices!!.getJSONObject(j)
                        .getString("createSql").replace('$' + "{TABLE_NAME}", table))
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
                sqlite.execSQL("INSERT INTO epub_passages VALUES ('plain','book',?,1,'OPS/chapter.xhtml',?,2,?,?,10)",
                    arrayOf<Any>(book.sha256, anchor.nodeTextSha256, anchor.end, selected))
                sqlite.execSQL("INSERT INTO book_organizations VALUES ('book',?,'READING',1,'Seri','Tür','[\"Etiket\"]','[\"Raf\"]')", arrayOf(book.sha256))
                sqlite.execSQL("INSERT INTO book_covers VALUES ('book',?,'cover','cover.png','image/png',20,?,8,12)", arrayOf(book.sha256, "d".repeat(64)))
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    val fields = entity.getJSONArray("fields")
                    val columns = (0 until fields.length()).joinToString(",") { fields.getJSONObject(it).getString("columnName") }
                    before[table] = columns to rows(sqlite.rawQuery("SELECT $columns FROM $table", null))
                }
                sqlite.version = 14
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { db ->
                    before.forEach { (table, snapshot) ->
                        assertEquals(table, snapshot.second, rows(db.openHelper.writableDatabase
                            .query("SELECT ${snapshot.first} FROM $table")))
                    }
                    val repo = RoomEpubPassageRepository(db)
                    val plain = repo.observe(book.id, book.sha256).first().single()
                    assertEquals(EpubPassage("plain", selected, anchor, 10), plain)
                    val edited = repo.updateAnnotation(book.id, book.sha256, plain, EpubHighlightColor.PINK, note)
                    assertEquals(1L, edited.revision)
                }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                val saved = RoomEpubPassageRepository(db).observe(book.id, book.sha256).first().single()
                assertEquals(EpubPassage("plain", selected, anchor, 10, EpubHighlightColor.PINK, note, 1), saved)
                assertEquals(26L, db.openHelper.writableDatabase.query("PRAGMA user_version").use {
                    assertTrue(it.moveToFirst()); it.getLong(0)
                })
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun `all colors and exact linked text survive close and reopen`() = runBlocking {
        val name = "ar-${UUID.randomUUID().toString().take(8)}.db"
        val expected = mutableListOf<EpubPassage>()
        try {
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                RoomLibraryRepository(db.books()).add(book)
                val repo = RoomEpubPassageRepository(db)
                EpubHighlightColor.entries.forEachIndexed { index, color ->
                    expected += repo.createAnnotation(book.id, book.sha256, selected,
                        anchor.copy(resourceHref = "OPS/$index.xhtml"), color, note)
                }
                expected += repo.createAnnotation(book.id, book.sha256, selected,
                    anchor.copy(resourceHref = "OPS/note.xhtml"), null, note)
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                val saved = RoomEpubPassageRepository(db).observe(book.id, book.sha256).first()
                assertEquals(expected.toSet(), saved.toSet())
                assertTrue(saved.all { it.text == selected && it.attachedNote == note && it.revision == 0L })
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun `color and note can clear independently without changing immutable source`() = runBlocking {
        withBook { db, repo ->
            val original = repo.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.YELLOW, note)
            val colorCleared = repo.updateAnnotation(book.id, book.sha256, original, null, note)
            assertEquals(original.copy(highlightColor = null, revision = 1), colorCleared)
            val noteCleared = repo.updateAnnotation(book.id, book.sha256, colorCleared, EpubHighlightColor.BLUE, null)
            assertEquals(original.copy(highlightColor = EpubHighlightColor.BLUE, attachedNote = null, revision = 2), noteCleared)
            val plain = repo.updateAnnotation(book.id, book.sha256, noteCleared, null, null)
            assertEquals(original.copy(highlightColor = null, attachedNote = null, revision = 3), plain)
            assertEquals(plain, repo.observe(book.id, book.sha256).first().single())
            assertEquals(1L, count(db))
        }
    }

    @Test fun `stale edits deletions and ABA cannot replace the latest annotation`() = runBlocking {
        withBook { db, repo ->
            repo.add(book.id, book.sha256, selected, anchor)
            val original = repo.observe(book.id, book.sha256).first().single()
            val first = repo.updateAnnotation(book.id, book.sha256, original, EpubHighlightColor.GREEN, note)
            val plainAgain = repo.updateAnnotation(book.id, book.sha256, first, null, null)
            assertEquals(original.copy(revision = 2), plainAgain)
            assertTrue(runCatching { repo.updateAnnotation(book.id, book.sha256, original, EpubHighlightColor.PINK, "stale") }
                .exceptionOrNull() is EpubPassageChangedException)
            assertTrue(runCatching { repo.removeExpected(book.id, book.sha256, original) }
                .exceptionOrNull() is EpubPassageChangedException)
            assertEquals(plainAgain, repo.observe(book.id, book.sha256).first().single())
            repo.removeExpected(book.id, book.sha256, plainAgain)
            assertEquals(0L, count(db))
        }
    }

    @Test fun `full expected record rejects forged immutable and annotation fields`() = runBlocking {
        withBook { _, repo ->
            val original = repo.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.YELLOW, note)
            val stale = listOf(original.copy(text = selected.replace('İ', 'I')),
                original.copy(anchor = anchor.copy(resourceHref = "OPS/other.xhtml")),
                original.copy(createdAt = original.createdAt + 1), original.copy(highlightColor = EpubHighlightColor.BLUE),
                original.copy(attachedNote = "different"), original.copy(revision = 1), original.copy(id = "missing"))
            stale.forEach { expected ->
                assertTrue(runCatching { repo.updateAnnotation(book.id, book.sha256, expected, null, "new") }
                    .exceptionOrNull() is EpubPassageChangedException)
                assertTrue(runCatching { repo.removeExpected(book.id, book.sha256, expected) }
                    .exceptionOrNull() is EpubPassageChangedException)
            }
            assertEquals(original, repo.observe(book.id, book.sha256).first().single())
        }
    }

    @Test fun `duplicate selected range never silently overwrites a plain or annotated passage`() = runBlocking {
        withBook { db, repo ->
            repo.add(book.id, book.sha256, selected, anchor)
            val original = repo.observe(book.id, book.sha256).first().single()
            assertTrue(runCatching { repo.createAnnotation(book.id, book.sha256, selected, anchor,
                EpubHighlightColor.PINK, note) }.exceptionOrNull() is EpubPassageChangedException)
            val edited = repo.updateAnnotation(book.id, book.sha256, original, EpubHighlightColor.PINK, note)
            assertTrue(runCatching { repo.createAnnotation(book.id, book.sha256, selected, anchor,
                EpubHighlightColor.BLUE, "other") }.exceptionOrNull() is EpubPassageChangedException)
            assertEquals(1L, count(db))
            assertEquals(edited, repo.observe(book.id, book.sha256).first().single())
        }
    }

    @Test fun `invalid empty or too long notes and empty create cannot write`() = runBlocking {
        withBook { db, repo ->
            for (invalid in listOf("", " \n\t", "x".repeat(2001))) {
                assertTrue(runCatching { repo.createAnnotation(book.id, book.sha256, selected, anchor,
                    EpubHighlightColor.GREEN, invalid) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertTrue(runCatching { repo.createAnnotation(book.id, book.sha256, selected, anchor,
                null, null) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0L, count(db))
            val maximal = repo.createAnnotation(book.id, book.sha256, selected, anchor, null, "🌿".repeat(1000))
            assertEquals(2000, maximal.attachedNote!!.length)
            assertTrue(runCatching { repo.updateAnnotation(book.id, book.sha256, maximal, null, "\n") }
                .exceptionOrNull() is IllegalArgumentException)
            assertEquals(maximal, repo.observe(book.id, book.sha256).first().single())
        }
    }

    @Test fun `malformed UTF16 cannot change exact quotes or linked notes during persistence`() = runBlocking {
        withBook { db, repo ->
            for (malformed in listOf("\uD800", "\uDC00", "a\uD800b", "\uD800\uD800")) {
                assertTrue(runCatching { repo.createAnnotation(book.id, book.sha256, malformed,
                    anchor.copy(start = 0, end = malformed.length), EpubHighlightColor.GREEN, null) }
                    .exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { repo.createAnnotation(book.id, book.sha256, selected, anchor,
                    EpubHighlightColor.GREEN, malformed) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals(0L, count(db))
            val original = repo.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.BLUE, note)
            assertTrue(runCatching { repo.updateAnnotation(book.id, book.sha256, original,
                EpubHighlightColor.PINK, "\uDC00") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(original, repo.observe(book.id, book.sha256).first().single())
        }
    }

    @Test fun `competing edits of the same revision have exactly one winner`() = runBlocking {
        withBook { _, repo ->
            val original = repo.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.YELLOW, note)
            val start = CompletableDeferred<Unit>()
            val writes = listOf(EpubHighlightColor.BLUE, EpubHighlightColor.PINK).map { color ->
                async(Dispatchers.IO) {
                    start.await()
                    runCatching { repo.updateAnnotation(book.id, book.sha256, original, color, color.name) }
                }
            }
            start.complete(Unit)
            val outcomes = writes.map { it.await() }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is EpubPassageChangedException)
            val winner = outcomes.single { it.isSuccess }.getOrThrow()
            assertEquals(1L, winner.revision)
            assertEquals(winner, repo.observe(book.id, book.sha256).first().single())
        }
    }

    @Test fun `changed hash missing book wrong format and deletion cascade remain enforced`() = runBlocking {
        withBook { db, repo ->
            val original = repo.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.YELLOW, note)
            assertNotNull(runCatching { repo.updateAnnotation(book.id, "wrong", original, null, null) }.exceptionOrNull())
            assertNotNull(runCatching { repo.removeExpected("missing", book.sha256, original) }.exceptionOrNull())
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = 'changed' WHERE id = 'book'") }
            assertTrue(repo.observe(book.id, book.sha256).first().isEmpty())
            assertTrue(repo.observe(book.id, "changed").first().isEmpty())
            assertNotNull(runCatching { repo.updateAnnotation(book.id, book.sha256, original, null, null) }.exceptionOrNull())
            assertNotNull(runCatching { repo.removeExpected(book.id, book.sha256, original) }.exceptionOrNull())
            withContext(Dispatchers.IO) {
                db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ?, format = 'PDF', pageCount = 1 WHERE id = 'book'", arrayOf(book.sha256))
            }
            assertNotNull(runCatching { repo.createAnnotation(book.id, book.sha256, selected,
                anchor.copy(resourceHref = "OPS/other.xhtml"), EpubHighlightColor.BLUE, null) }.exceptionOrNull())
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = 'book'") }
            assertEquals(0L, count(db))
        }
    }

    @Test fun `guard rechecks source readiness after waiting behind the shared mutex`() = runBlocking {
        withBook { db, _ ->
            val mutex = Mutex(locked = true)
            var ready = true
            val guards = AtomicInteger()
            val repo = RoomEpubPassageRepository(db, fileMutex = mutex, validateAccess = {
                guards.incrementAndGet(); check(ready) { "Recovery pending" }
            })
            val started = CompletableDeferred<Unit>()
            val write = async(Dispatchers.IO) {
                started.complete(Unit)
                runCatching { repo.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.YELLOW, note) }.exceptionOrNull()
            }
            started.await()
            assertEquals(0, guards.get())
            assertFalse(write.isCompleted)
            ready = false
            mutex.unlock()
            assertEquals("Recovery pending", write.await()?.message)
            assertEquals(1, guards.get())
            assertEquals(0L, count(db))
            assertNotNull(runCatching { repo.observe(book.id, book.sha256).first() }.exceptionOrNull())
        }
    }

    @Test fun `cancelled queued write and guard cancellation never commit or become success`() = runBlocking {
        withBook { db, _ ->
            val mutex = Mutex(locked = true)
            val guards = AtomicInteger()
            val repo = RoomEpubPassageRepository(db, fileMutex = mutex, validateAccess = { guards.incrementAndGet() })
            val started = CompletableDeferred<Unit>()
            val pending = launch(Dispatchers.IO) {
                started.complete(Unit)
                repo.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.BLUE, note)
            }
            started.await()
            pending.cancelAndJoin()
            mutex.unlock()
            assertTrue(pending.isCancelled)
            assertEquals(0, guards.get())
            assertEquals(0L, count(db))
            val cancelledGuard = RoomEpubPassageRepository(db, validateAccess = { throw CancellationException("Source cancelled") })
            assertTrue(runCatching { cancelledGuard.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.BLUE, note) }
                .exceptionOrNull() is CancellationException)
            assertTrue(runCatching { cancelledGuard.observe(book.id, book.sha256).first() }
                .exceptionOrNull() is CancellationException)
            assertEquals(0L, count(db))
        }
    }

    @Test fun `closed database cannot reopen through annotation read or write`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repo = RoomEpubPassageRepository(db)
        RoomLibraryRepository(db.books()).add(book)
        db.close()
        assertNotNull(runCatching { repo.observe(book.id, book.sha256).first() }.exceptionOrNull())
        assertNotNull(runCatching { repo.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.YELLOW, note) }.exceptionOrNull())
        assertFalse(db.isOpen)
    }

    @Test fun `nonempty annotation limit allows edits clears and plain saved passages`() = runBlocking {
        withBook { db, repo ->
            withContext(Dispatchers.IO) {
                val sql = db.openHelper.writableDatabase
                sql.beginTransaction()
                try {
                    repeat(MAX_EPUB_ANNOTATIONS_PER_BOOK) { index ->
                        sql.execSQL("INSERT INTO epub_passages (id,bookId,contentSha256,anchorVersion,resourceHref,nodeTextSha256,startOffset,endOffset,text,createdAt,highlightColor) VALUES (?, ?, ?, 1, ?, ?, 0, 1, 'x', ?, 'YELLOW')",
                            arrayOf<Any>("p$index", book.id, book.sha256, "OPS/$index.xhtml", "b".repeat(64), index))
                    }
                    sql.setTransactionSuccessful()
                } finally { sql.endTransaction() }
            }
            assertTrue(runCatching { repo.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.GREEN, note) }
                .exceptionOrNull() is EpubAnnotationLimitException)
            repo.add(book.id, book.sha256, selected, anchor)
            val plain = repo.observe(book.id, book.sha256).first().first { it.anchor == anchor }
            assertTrue(runCatching { repo.updateAnnotation(book.id, book.sha256, plain, null, note) }
                .exceptionOrNull() is EpubAnnotationLimitException)
            val current = repo.observe(book.id, book.sha256).first().first { it.id == "p0" }
            val updated = repo.updateAnnotation(book.id, book.sha256, current, EpubHighlightColor.PINK, note)
            assertEquals(1L, updated.revision)
            repo.updateAnnotation(book.id, book.sha256, updated, null, null)
            val annotated = repo.updateAnnotation(book.id, book.sha256, plain, null, note)
            assertEquals(note, annotated.attachedNote)
            assertEquals(MAX_EPUB_ANNOTATIONS_PER_BOOK + 1L, count(db))
        }
    }

    @Test fun `invalid stored annotation fields fail reading rather than drop or default`() = runBlocking {
        withBook { db, repo ->
            val original = repo.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.YELLOW, note)
            for (values in listOf("highlightColor = 'FUTURE'", "attachedNote = ''", "attachedNote = X'6e6f7465'",
                "revision = -1", "revision = 0.5", "revision = X'31'")) {
                withContext(Dispatchers.IO) {
                    db.openHelper.writableDatabase.execSQL("UPDATE epub_passages SET $values WHERE id = ?", arrayOf(original.id))
                }
                assertNotNull(runCatching { repo.observe(book.id, book.sha256).first() }.exceptionOrNull())
                withContext(Dispatchers.IO) {
                    db.openHelper.writableDatabase.execSQL("UPDATE epub_passages SET highlightColor = 'YELLOW', attachedNote = ?, revision = 0 WHERE id = ?", arrayOf(note, original.id))
                }
            }
        }
    }

    @Test fun `revision cannot overflow and same value save still invalidates old draft`() = runBlocking {
        withBook { db, repo ->
            val original = repo.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.YELLOW, note)
            val saved = repo.updateAnnotation(book.id, book.sha256, original, original.highlightColor, original.attachedNote)
            assertEquals(original.copy(revision = 1), saved)
            assertTrue(runCatching { repo.removeExpected(book.id, book.sha256, original) }.exceptionOrNull() is EpubPassageChangedException)
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("UPDATE epub_passages SET revision = ? WHERE id = ?", arrayOf<Any>(Long.MAX_VALUE, original.id)) }
            val exhausted = repo.observe(book.id, book.sha256).first().single()
            assertNotNull(runCatching { repo.updateAnnotation(book.id, book.sha256, exhausted, null, null) }.exceptionOrNull())
            assertEquals(exhausted, repo.observe(book.id, book.sha256).first().single())
            repo.removeExpected(book.id, book.sha256, exhausted)
            assertEquals(0L, count(db))
        }
    }

    @Test fun `live observation refreshes on create update clear and delete`() = runBlocking {
        withBook { _, repo ->
            val events = Channel<List<EpubPassage>>(Channel.UNLIMITED)
            val observer = launch(Dispatchers.IO) { repo.observe(book.id, book.sha256).collect { events.send(it) } }
            try {
                assertTrue(withTimeout(5_000) { events.receive() }.isEmpty())
                val first = repo.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.YELLOW, note)
                assertEquals(listOf(first), withTimeout(5_000) { events.receive() })
                val second = repo.updateAnnotation(book.id, book.sha256, first, EpubHighlightColor.BLUE, note)
                assertEquals(listOf(second), withTimeout(5_000) { events.receive() })
                val plain = repo.updateAnnotation(book.id, book.sha256, second, null, null)
                assertEquals(listOf(plain), withTimeout(5_000) { events.receive() })
                repo.removeExpected(book.id, book.sha256, plain)
                assertTrue(withTimeout(5_000) { events.receive() }.isEmpty())
            } finally { observer.cancelAndJoin(); events.close() }
        }
    }

    @Test @Config(sdk = [28]) fun `reader and writer leases support older framework sqlite`() = runBlocking {
        withBook { _, repo ->
            val saved = repo.createAnnotation(book.id, book.sha256, selected, anchor, EpubHighlightColor.GREEN, note)
            assertEquals(saved, repo.observe(book.id, book.sha256).first().single())
            val updated = repo.updateAnnotation(book.id, book.sha256, saved, null, note)
            repo.removeExpected(book.id, book.sha256, updated)
            assertTrue(repo.observe(book.id, book.sha256).first().isEmpty())
        }
    }

    private suspend fun withBook(block: suspend (LibraryDatabase, RoomEpubPassageRepository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            RoomLibraryRepository(db.books()).add(book)
            block(db, RoomEpubPassageRepository(db))
        } finally { db.close() }
    }

    private suspend fun count(db: LibraryDatabase): Long = withContext(Dispatchers.IO) {
        db.openHelper.writableDatabase.compileStatement("SELECT count(*) FROM epub_passages").simpleQueryForLong()
    }

    private fun rows(cursor: Cursor) = cursor.use { buildList {
        while (it.moveToNext()) add(List(it.columnCount) { column -> if (it.isNull(column)) null else it.getString(column) })
    } }
}
