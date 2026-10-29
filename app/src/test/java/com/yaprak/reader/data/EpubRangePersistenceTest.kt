package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.exporting.RoomBookStudySource
import com.yaprak.reader.data.exporting.StudyExportException
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.notes.LibraryNoteSource
import com.yaprak.reader.domain.notes.LibraryNotesSourceException
import com.yaprak.reader.domain.passages.*
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceRangeSegment
import com.yaprak.reader.reader.SourceTextAnchor
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
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
class EpubRangePersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("book", "Kitap", "Yazar", BookFormat.EPUB,
        "source.epub", "book.epub", 100, "a".repeat(64), 1)
    private val anchor = SourceRangeAnchor("OPS/first.xhtml", listOf(
        SourceRangeSegment("0/0", 2, 6, "b".repeat(64), "İğne"),
        SourceRangeSegment("1/0/0", 0, 4, "c".repeat(64), "🌿 x")))
    private val preview = "İğne\n🌿 x"
    private val note = "Kendi notum — 🌿"
    private val oldAnchor = SourceTextAnchor("OPS/old.xhtml", 0, 3, "d".repeat(64))

    @Test fun unionAndReopenPreserveBoth() = runBlocking {
        val name = "er-${UUID.randomUUID().toString().take(8)}.db"
        var database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            database.books().insert(book.toEntity())
            val repo = RoomEpubPassageRepository(database)
            repo.add(book.id, book.sha256, "old", oldAnchor)
            val saved = repo.createAnnotation(book.id, book.sha256, preview, anchor, EpubHighlightColor.PINK, note)
            assertEquals(1L, count(database, "epub_passages"))
            assertEquals(1L, count(database, "epub_range_passages"))
            assertEquals(2, repo.observe(book.id, book.sha256).first().size)
            database.close()
            database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            val values = RoomEpubPassageRepository(database).observe(book.id, book.sha256).first()
            assertEquals(saved, values.single { it.anchor is SourceRangeAnchor })
            assertEquals(oldAnchor, values.single { it.anchor is SourceTextAnchor }.anchor)
            assertEquals(preview, saved.text)
            assertNotEquals(preview, anchor.segments.joinToString("") { it.selectedText })
            val raw = database.openHelper.writableDatabase.query("SELECT anchorJson, anchorKey FROM epub_range_passages").use {
                assertTrue(it.moveToFirst()); it.getString(0) to it.getString(1)
            }
            assertEquals(EpubRangeAnchorCodec.encode(anchor), raw.first)
            assertEquals(EpubRangeAnchorCodec.key(anchor), raw.second)
            assertFalse(raw.first.contains("bodyTopology"))
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun groupedEditsAndAbaAreAtomic() = runBlocking {
        fixture { database ->
            val repo = RoomEpubPassageRepository(database)
            val saved = repo.createAnnotation(book.id, book.sha256, preview, anchor, EpubHighlightColor.YELLOW, note)
            val blue = repo.updateAnnotation(book.id, book.sha256, saved, EpubHighlightColor.BLUE, null)
            val back = repo.updateAnnotation(book.id, book.sha256, blue, EpubHighlightColor.YELLOW, note)
            assertEquals(saved.copy(revision = 2), back)
            assertTrue(runCatching { repo.updateAnnotation(book.id, book.sha256, saved, null, "stale") }
                .exceptionOrNull() is EpubPassageChangedException)
            assertTrue(runCatching { repo.removeExpected(book.id, book.sha256, saved) }
                .exceptionOrNull() is EpubPassageChangedException)
            val forged = back.copy(anchor = anchor.copy(segments = anchor.segments.mapIndexed { index, segment ->
                if (index == 0) segment.copy(start = 3, end = 7) else segment }))
            assertTrue(runCatching { repo.removeExpected(book.id, book.sha256, forged) }
                .exceptionOrNull() is EpubPassageChangedException)
            assertEquals(listOf(back), repo.observe(book.id, book.sha256).first())
            assertEquals(1L, count(database, "epub_range_passages"))
            repo.removeExpected(book.id, book.sha256, back)
            assertEquals(0L, count(database, "epub_range_passages"))
        }
    }

    @Test fun duplicateRangeNeverOverwrites() = runBlocking {
        fixture { database ->
            val repo = RoomEpubPassageRepository(database)
            repo.add(book.id, book.sha256, preview, anchor)
            val original = repo.observe(book.id, book.sha256).first().single()
            assertTrue(runCatching { repo.createAnnotation(book.id, book.sha256, preview, anchor,
                EpubHighlightColor.PINK, note) }.exceptionOrNull() is EpubPassageChangedException)
            assertEquals("EPUB passage already saved", runCatching { repo.add(book.id, book.sha256, preview, anchor) }
                .exceptionOrNull()?.message)
            val next = repo.updateAnnotation(book.id, book.sha256, original, EpubHighlightColor.GREEN, note)
            assertEquals(original.copy(highlightColor = EpubHighlightColor.GREEN, attachedNote = note, revision = 1), next)
            assertEquals(1L, count(database, "epub_range_passages"))
        }
    }

    @Test fun competingRevisionHasOneWinner() = runBlocking {
        fixture { database ->
            val mutex = Mutex()
            val repos = listOf(RoomEpubPassageRepository(database, fileMutex = mutex),
                RoomEpubPassageRepository(database, fileMutex = mutex))
            val saved = repos[0].createAnnotation(book.id, book.sha256, preview, anchor, EpubHighlightColor.YELLOW, note)
            val start = CompletableDeferred<Unit>()
            val writes = repos.mapIndexed { index, repo -> async(Dispatchers.IO) {
                start.await(); runCatching { repo.updateAnnotation(book.id, book.sha256, saved,
                    EpubHighlightColor.entries[index], "not-$index") }
            } }
            start.complete(Unit)
            val outcomes = writes.awaitAll()
            assertEquals(1, outcomes.count { it.isSuccess })
            assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is EpubPassageChangedException)
            assertEquals(outcomes.single { it.isSuccess }.getOrThrow(), repos[0].observe(book.id, book.sha256).first().single())
        }
    }

    @Test fun combinedActiveCapacityKeepsPlain() = runBlocking {
        fixture { database ->
            val sql = database.openHelper.writableDatabase
            sql.beginTransaction()
            try {
                repeat(MAX_EPUB_ANNOTATIONS_PER_BOOK - 1) { index ->
                    sql.execSQL("INSERT INTO epub_passages (id,bookId,contentSha256,anchorVersion,resourceHref,nodeTextSha256,startOffset,endOffset,text,createdAt,highlightColor) VALUES (?,?,?,1,?,?,0,1,'x',?,'YELLOW')",
                        arrayOf<Any>("p$index", book.id, book.sha256, "OPS/$index.xhtml", "b".repeat(64), index))
                }
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
            val repo = RoomEpubPassageRepository(database)
            val full = repo.createAnnotation(book.id, book.sha256, preview, anchor, EpubHighlightColor.BLUE, note)
            val another = anchor.copy(resourceHref = "OPS/plain.xhtml")
            repo.add(book.id, book.sha256, preview, another)
            val plain = repo.observe(book.id, book.sha256).first().single { it.anchor == another }
            assertTrue(runCatching { repo.updateAnnotation(book.id, book.sha256, plain, null, note) }
                .exceptionOrNull() is EpubAnnotationLimitException)
            assertTrue(runCatching { repo.createAnnotation(book.id, book.sha256, "old", oldAnchor, EpubHighlightColor.PINK, null) }
                .exceptionOrNull() is EpubAnnotationLimitException)
            val changed = repo.updateAnnotation(book.id, book.sha256, full, EpubHighlightColor.GREEN, note)
            repo.updateAnnotation(book.id, book.sha256, changed, null, null)
            assertEquals(note, repo.updateAnnotation(book.id, book.sha256, plain, null, note).attachedNote)
            assertEquals(MAX_EPUB_ANNOTATIONS_PER_BOOK + 1, repo.observe(book.id, book.sha256).first().size)
        }
    }

    @Test fun malformedRowFailsAllConsumers() = runBlocking {
        fixture { database ->
            val repo = RoomEpubPassageRepository(database)
            val saved = repo.createAnnotation(book.id, book.sha256, preview, anchor, EpubHighlightColor.BLUE, note)
            val mutations = listOf<Pair<String, Any>>("anchorVersion" to 2.5, "anchorVersion" to 4294967298L,
                "anchorJson" to EpubRangeAnchorCodec.encode(anchor).replace("\"start\":2", "\"start\":2.0"),
                "anchorKey" to "f".repeat(64), "resourceHref" to "OPS/different.xhtml", "revision" to 1.5,
                "revision" to -1, "highlightColor" to "UNKNOWN", "attachedNote" to "x".repeat(2001))
            for ((field, value) in mutations) {
                database.openHelper.writableDatabase.execSQL("UPDATE epub_range_passages SET $field = ? WHERE id = ?",
                    arrayOf(value, saved.id))
                assertTrue(field, runCatching { repo.observe(book.id, book.sha256).first() }.isFailure)
                assertTrue(field, runCatching { RoomLibraryNotesRepository(database).observe("").first() }
                    .exceptionOrNull() is LibraryNotesSourceException)
                assertTrue(field, runCatching { RoomBookStudySource(database).snapshotRecords(book.id, book.sha256) }
                    .exceptionOrNull() is StudyExportException)
                database.openHelper.writableDatabase.execSQL("UPDATE epub_range_passages SET anchorVersion=2,anchorJson=?,anchorKey=?,resourceHref=?,revision=0,highlightColor='BLUE',attachedNote=? WHERE id=?",
                    arrayOf(EpubRangeAnchorCodec.encode(anchor), EpubRangeAnchorCodec.key(anchor), anchor.resourceHref, note, saved.id))
            }
            assertEquals(saved, repo.observe(book.id, book.sha256).first().single())
        }
    }

    @Test fun globalDuplicateIdsFailClosed() = runBlocking {
        fixture { database ->
            val repo = RoomEpubPassageRepository(database)
            val saved = repo.createAnnotation(book.id, book.sha256, preview, anchor, EpubHighlightColor.GREEN, note)
            val other = book.copy(id = "other", sha256 = "e".repeat(64), storageName = "other.epub")
            database.books().insert(other.toEntity())
            database.openHelper.writableDatabase.execSQL("INSERT INTO epub_passages (id,bookId,contentSha256,anchorVersion,resourceHref,nodeTextSha256,startOffset,endOffset,text,createdAt) VALUES (?,?,?,1,'OPS/old.xhtml',?,0,3,'old',1)",
                arrayOf(saved.id, other.id, other.sha256, oldAnchor.nodeTextSha256))
            assertTrue(runCatching { repo.observe(book.id, book.sha256).first() }.isFailure)
            assertTrue(runCatching { repo.observe("missing", book.sha256).first() }.isFailure)
            assertTrue(runCatching { repo.updateAnnotation(book.id, book.sha256, saved, null, note) }.isFailure)
            assertTrue(runCatching { repo.add(book.id, book.sha256, "old", oldAnchor) }.isFailure)
            assertTrue(runCatching { RoomLibraryNotesRepository(database).observe("").first() }.exceptionOrNull() is LibraryNotesSourceException)
            assertTrue(runCatching { RoomLibraryNotesRepository(database).find(book.id, book.sha256, saved.id, LibraryNoteSource.EPUB_PASSAGE) }
                .exceptionOrNull() is LibraryNotesSourceException)
            assertTrue(runCatching { RoomBookStudySource(database).snapshotRecords(book.id, book.sha256) }
                .exceptionOrNull() is StudyExportException)
            assertEquals(1L, count(database, "epub_range_passages"))
        }
    }

    @Test fun notesAndEveryExportIncludeRange() = runBlocking {
        fixture { database ->
            val saved = RoomEpubPassageRepository(database).createAnnotation(book.id, book.sha256, preview, anchor,
                EpubHighlightColor.PINK, note)
            val notes = RoomLibraryNotesRepository(database)
            val result = notes.observe("kendi yazar").first()
            assertEquals(1, result.totalCount)
            assertEquals(anchor, result.entries.single().sourceAnchor)
            assertEquals(preview, result.entries.single().sourceText)
            assertEquals(result.entries.single(), notes.find(book.id, book.sha256, saved.id, LibraryNoteSource.EPUB_PASSAGE))
            val source = RoomBookStudySource(database)
            for (format in listOf(StudyExportFormat.TEXT, StudyExportFormat.MARKDOWN)) {
                val exported = source.snapshot(book.id, book.sha256, format)
                assertEquals(1, exported.passageCount)
                assertEquals(1, exported.noteCount)
                val text = exported.utf8.toString(Charsets.UTF_8)
                assertTrue(text.contains("İğne")); assertTrue(text.contains("🌿 x")); assertTrue(text.contains(note))
            }
            val typed = source.snapshotRecords(book.id, book.sha256)
            assertEquals(1, typed.noteCount)
            assertEquals(anchor, typed.passages.single().anchor)
            assertEquals(preview, typed.passages.single().text)
        }
    }

    @Test fun changedSourceAndCascadeStayScoped() = runBlocking {
        fixture { database ->
            val repo = RoomEpubPassageRepository(database)
            val saved = repo.createAnnotation(book.id, book.sha256, preview, anchor, EpubHighlightColor.BLUE, note)
            val changed = "e".repeat(64)
            database.openHelper.writableDatabase.execSQL("UPDATE books SET sha256=? WHERE id=?", arrayOf(changed, book.id))
            assertTrue(repo.observe(book.id, book.sha256).first().isEmpty())
            assertTrue(repo.observe(book.id, changed).first().isEmpty())
            assertTrue(RoomLibraryNotesRepository(database).observe("").first().entries.isEmpty())
            assertTrue(runCatching { repo.removeExpected(book.id, book.sha256, saved) }.isFailure)
            assertTrue(runCatching { repo.createAnnotation(book.id, book.sha256, preview, anchor, EpubHighlightColor.GREEN, note) }.isFailure)
            database.books().deleteExact(book.id, changed)
            assertEquals(0L, count(database, "epub_range_passages"))
        }
    }

    @Test fun queuedCancelAndRecoveryDoNotWrite() = runBlocking {
        fixture { database ->
            val mutex = Mutex(locked = true)
            var ready = true
            val repo = RoomEpubPassageRepository(database, fileMutex = mutex, validateAccess = { check(ready) { "Recovery pending" } })
            val pending = launch(start = CoroutineStart.UNDISPATCHED) {
                repo.createAnnotation(book.id, book.sha256, preview, anchor, EpubHighlightColor.BLUE, note)
            }
            pending.cancelAndJoin(); mutex.unlock()
            assertTrue(pending.isCancelled)
            assertEquals(0L, count(database, "epub_range_passages"))
            mutex.lock()
            val blocked = async(Dispatchers.IO) { runCatching { repo.add(book.id, book.sha256, preview, anchor) } }
            ready = false; mutex.unlock()
            assertEquals("Recovery pending", blocked.await().exceptionOrNull()?.message)
            assertEquals(0L, count(database, "epub_range_passages"))
            assertTrue(runCatching { repo.observe(book.id, book.sha256).first() }.isFailure)
        }
    }

    @Test fun insertCancellationRollsBackWholeGroup() = runBlocking {
        val writing = AtomicReference<Job?>()
        val cancel = AtomicBoolean(false)
        val inserted = AtomicInteger(0)
        // Room's QueryCallback does not intercept SupportSQLiteDatabase.insert(ContentValues).
        // Wrap that native boundary so cancellation occurs after a real insert and before commit.
        val factory = object : SupportSQLiteOpenHelper.Factory {
            override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
                fun wrap(sql: SupportSQLiteDatabase) = object : SupportSQLiteDatabase by sql {
                    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long {
                        val id = sql.insert(table, conflictAlgorithm, values)
                        if (cancel.get() && table == "epub_range_passages") {
                            assertTrue(id != -1L)
                            assertEquals(1L, sql.query("SELECT COUNT(*) FROM epub_range_passages").use {
                                assertTrue(it.moveToFirst()); it.getLong(0)
                            })
                            inserted.incrementAndGet()
                            writing.get()!!.cancel()
                        }
                        return id
                    }
                }
                return object : SupportSQLiteOpenHelper by helper {
                    override val writableDatabase get() = wrap(helper.writableDatabase)
                    override val readableDatabase get() = wrap(helper.readableDatabase)
                }
            }
        }
        fixture(factory) { database ->
            val repo = RoomEpubPassageRepository(database)
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                repo.createAnnotation(book.id, book.sha256, preview, anchor, EpubHighlightColor.BLUE, note)
            }
            writing.set(job); cancel.set(true); job.start(); withTimeout(5000) { job.join() }
            cancel.set(false)
            assertEquals(1, inserted.get())
            assertTrue(job.isCancelled)
            assertEquals(0L, count(database, "epub_range_passages"))
            assertEquals(0L, count(database, "epub_passages"))
            assertTrue(repo.observe(book.id, book.sha256).first().isEmpty())
        }
    }

    @Test fun nativeAbortKeepsOriginalGroup() = runBlocking {
        fixture { database ->
            val repo = RoomEpubPassageRepository(database)
            val saved = repo.createAnnotation(book.id, book.sha256, preview, anchor, EpubHighlightColor.BLUE, note)
            val sql = database.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER reject_range BEFORE UPDATE ON epub_range_passages BEGIN SELECT RAISE(ABORT,'controlled'); END")
            assertTrue(runCatching { repo.updateAnnotation(book.id, book.sha256, saved, EpubHighlightColor.PINK, "new") }.isFailure)
            assertEquals(saved, repo.observe(book.id, book.sha256).first().single())
            assertEquals(1L, count(database, "epub_range_passages"))
        }
    }

    @Test fun closedRoomNeverReopens() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        database.books().insert(book.toEntity())
        val repo = RoomEpubPassageRepository(database)
        database.close()
        assertTrue(runCatching { repo.observe(book.id, book.sha256).first() }.isFailure)
        assertTrue(runCatching { repo.createAnnotation(book.id, book.sha256, preview, anchor, EpubHighlightColor.BLUE, note) }.isFailure)
        assertFalse(database.isOpen)
    }

    @Test fun migration17PreservesEveryOldTable() = runBlocking {
        val name = "em-${UUID.randomUUID().toString().take(8)}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/17.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val snapshots = linkedMapOf<String, Pair<String, List<List<String?>>>>()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    sql.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    for (j in 0 until (indices?.length() ?: 0)) sql.execSQL(indices!!.getJSONObject(j).getString("createSql")
                        .replace('$' + "{TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) sql.execSQL(setup.getString(index))
                sql.execSQL("INSERT INTO books VALUES ('book','Kitap','Yazar','EPUB','source.epub','book.epub',100,?,1,NULL)", arrayOf(book.sha256))
                sql.execSQL("INSERT INTO books VALUES ('pdf','PDF',NULL,'PDF','source.pdf','pdf.pdf',200,?,2,5)", arrayOf("e".repeat(64)))
                sql.execSQL("INSERT INTO epub_passages VALUES ('legacy','book',?,1,'OPS/old.xhtml',?,0,3,'old',10,'BLUE','Old note',3)", arrayOf(book.sha256, oldAnchor.nodeTextSha256))
                sql.execSQL("INSERT INTO book_notes VALUES ('note','book',?,'General note',11,NULL)", arrayOf(book.sha256))
                sql.execSQL("INSERT INTO reading_progress VALUES ('book',?,1,'saved','OPS/old.xhtml',5)", arrayOf(book.sha256))
                sql.execSQL("INSERT INTO saved_words VALUES ('word','book',?,'leaf','yaprak',6)", arrayOf(book.sha256))
                sql.execSQL("INSERT INTO book_pdf_view_preferences VALUES ('pdf',?,7,14,'WIDTH',1)", arrayOf("e".repeat(64)))
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val fields = entity.getJSONArray("fields")
                    val columns = (0 until fields.length()).joinToString(",") { fields.getJSONObject(it).getString("columnName") }
                    val table = entity.getString("tableName")
                    snapshots[table] = columns to rows(sql.rawQuery("SELECT $columns FROM $table", null))
                }
                sql.version = 17
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26)
                .build().useDatabase { database ->
                    snapshots.forEach { (table, before) ->
                        assertEquals(table, before.second, rows(database.openHelper.writableDatabase.query("SELECT ${before.first} FROM $table")))
                    }
                    assertEquals(0L, count(database, "epub_range_passages"))
                    val legacy = RoomEpubPassageRepository(database).observe(book.id, book.sha256).first().single()
                    assertEquals(oldAnchor, legacy.anchor); assertEquals(3L, legacy.revision)
                    RoomEpubPassageRepository(database).createAnnotation(book.id, book.sha256, preview, anchor, EpubHighlightColor.PINK, note)
                }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { database ->
                assertEquals(2, RoomEpubPassageRepository(database).observe(book.id, book.sha256).first().size)
                assertEquals(26L, database.openHelper.writableDatabase.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); it.getLong(0) })
            }
        } finally { context.deleteDatabase(name) }
    }

    private suspend fun fixture(factory: SupportSQLiteOpenHelper.Factory? = null,
        block: suspend (LibraryDatabase) -> Unit) {
        val builder = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
        if (factory != null) builder.openHelperFactory(factory)
        val database = builder.build()
        try { database.books().insert(book.toEntity()); block(database) } finally { database.close() }
    }

    private fun count(database: LibraryDatabase, table: String) = database.openHelper.writableDatabase
        .compileStatement("SELECT COUNT(*) FROM $table").simpleQueryForLong()

    private fun rows(cursor: Cursor) = cursor.use { buildList {
        while (it.moveToNext()) add(List(it.columnCount) { column -> if (it.isNull(column)) null else it.getString(column) })
    } }
}
