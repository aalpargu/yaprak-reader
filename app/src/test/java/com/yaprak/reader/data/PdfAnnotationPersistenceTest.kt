package com.yaprak.reader.data

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.reader.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room/SQLite/file guard. The original verifier is controlled separately from decoder acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PdfAnnotationPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("pdf", "Kitap", "Yazar", BookFormat.PDF, "source.pdf", "pdf.pdf", 100, "a".repeat(64), 1, pageCount = 12)
    private val anchor = PdfSourceAnchor(3, 300, 600, PdfPagePoint(60, 100), PdfPagePoint(180, 100),
        listOf(PdfTextBounds(60.0, 90.0, 180.0, 110.0)))
    private val text = " alpha 🌿\nı "
    private fun repo(database: LibraryDatabase, verify: PdfAnnotationOriginalSource = PdfAnnotationOriginalSource { _, _, _ -> },
        mutex: Mutex = Mutex(), guard: () -> Unit = {}) = RoomPdfAnnotationRepository(database, verify, mutex, guard)
    private suspend fun fixture(block: suspend (LibraryDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try { database.books().insert(book.toEntity()); block(database) } finally { database.close() }
    }
    private fun count(database: LibraryDatabase) = database.openHelper.writableDatabase
        .compileStatement("SELECT COUNT(*) FROM pdf_annotations").simpleQueryForLong()

    @Test fun createReopenEditAndRemovePreserveExactTextColorNoteAndNativeAnchor() = runBlocking {
        val name = "pa-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            database.books().insert(book.toEntity()); var proofs = 0
            val saved = repo(database, PdfAnnotationOriginalSource { actual, selected, evidence ->
                assertEquals(book.copy(lastReadAt = null), actual); assertEquals(text, selected); assertEquals(anchor, evidence); proofs++
            }).create(book.id, book.sha256, text, anchor, PdfHighlightColor.GREEN, " note 🌿 ")
            assertEquals(1, proofs); database.close()
            database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            val records = repo(database); assertEquals(listOf(saved), records.observe(book.id, book.sha256).first())
            val changed = records.update(book.id, book.sha256, saved, null, "second")
            assertEquals(saved.copy(highlightColor = null, attachedNote = "second", revision = 1), changed)
            records.verifyExisting(book.id, book.sha256, listOf(changed))
            records.remove(book.id, book.sha256, changed); assertEquals(0L, count(database))
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun staleEditorAndDeleteCannotOverwriteAColorNoteAbaOrADifferentExpectedRecord() = runBlocking {
        fixture { database ->
            val records = repo(database)
            val first = records.create(book.id, book.sha256, text, anchor, PdfHighlightColor.YELLOW, "first")
            val second = records.update(book.id, book.sha256, first, PdfHighlightColor.BLUE, null)
            val back = records.update(book.id, book.sha256, second, PdfHighlightColor.YELLOW, "first")
            assertEquals(first.copy(revision = 2), back)
            assertTrue(runCatching { records.update(book.id, book.sha256, first, PdfHighlightColor.PINK, null) }.exceptionOrNull() is PdfAnnotationChangedException)
            assertTrue(runCatching { records.remove(book.id, book.sha256, first) }.exceptionOrNull() is PdfAnnotationChangedException)
            assertTrue(runCatching { records.verifyExisting(book.id, book.sha256, listOf(first)) }.exceptionOrNull() is PdfAnnotationChangedException)
            assertEquals(listOf(back), records.observe(book.id, book.sha256).first())
        }
    }
    @Test fun duplicateAnchorFailsAtomicallyAndRealThousandRecordQuotaCannotBeBypassed() = runBlocking {
        fixture { database ->
            val records = repo(database)
            records.create(book.id, book.sha256, text, anchor, PdfHighlightColor.PINK, null)
            val sameSelection = PdfSourceAnchor(3, 300, 600, PdfPagePoint(61, 100), PdfPagePoint(181, 100), anchor.bounds)
            assertTrue(runCatching { records.create(book.id, book.sha256, text, sameSelection, PdfHighlightColor.BLUE, null) }
                .exceptionOrNull() is PdfAnnotationChangedException)
            for (index in 1 until 1000) {
                val other = PdfSourceAnchor(3, 2000, 600, PdfPagePoint(index, 100), PdfPagePoint(index, 100),
                    listOf(PdfTextBounds(index.toDouble(), 90.0, index + .5, 110.0)))
                database.openHelper.writableDatabase.insert("pdf_annotations", 0, values(other, "word"))
            }
            val next = PdfSourceAnchor(4, 300, 600, anchor.start, anchor.stop, anchor.bounds)
            assertTrue(runCatching { records.create(book.id, book.sha256, text, next, PdfHighlightColor.BLUE, null) }
                .exceptionOrNull() is PdfAnnotationLimitException)
            assertEquals(1000L, count(database)); assertEquals(1000, records.observe(book.id, book.sha256).first().size)
        }
    }
    @Test fun wrongHashFormatAndPageNeverReachTheOriginalVerifier() = runBlocking {
        fixture { database ->
            var proofs = 0; val records = repo(database, PdfAnnotationOriginalSource { _, _, _ -> proofs++ })
            val outside = PdfSourceAnchor(12, 300, 600, anchor.start, anchor.stop, anchor.bounds)
            assertTrue(runCatching { records.create(book.id, "b".repeat(64), text, anchor, PdfHighlightColor.YELLOW, null) }.isFailure)
            assertTrue(runCatching { records.create(book.id, book.sha256, text, outside, PdfHighlightColor.YELLOW, null) }.isFailure)
            database.openHelper.writableDatabase.execSQL("UPDATE books SET format = 'EPUB', pageCount = NULL WHERE id = 'pdf'")
            assertTrue(runCatching { records.create(book.id, book.sha256, text, anchor, PdfHighlightColor.YELLOW, null) }.isFailure)
            assertEquals(0, proofs); assertEquals(0L, count(database))
        }
    }
    @Test fun sourceFailureAndMetadataReplacementDuringNativeProofCannotCommit() = runBlocking {
        fixture { database ->
            val failed = repo(database, PdfAnnotationOriginalSource { _, _, _ -> throw PdfAnnotationSourceException() })
            assertTrue(runCatching { failed.create(book.id, book.sha256, text, anchor, PdfHighlightColor.BLUE, null) }.exceptionOrNull() is PdfAnnotationSourceException)
            val replaced = repo(database, PdfAnnotationOriginalSource { _, _, _ ->
                database.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ? WHERE id = 'pdf'", arrayOf("b".repeat(64)))
            })
            assertTrue(runCatching { replaced.create(book.id, book.sha256, text, anchor, PdfHighlightColor.BLUE, null) }.exceptionOrNull() is PdfAnnotationSourceException)
            assertEquals(0L, count(database))
        }
    }
    @Test fun callerCancellationDuringSourceProofPropagatesAndLeavesNoRecord() = runBlocking {
        fixture { database ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val records = repo(database, PdfAnnotationOriginalSource { _, _, _ -> entered.complete(Unit); release.await() })
            val pending = async { records.create(book.id, book.sha256, text, anchor, PdfHighlightColor.YELLOW, null) }
            entered.await(); pending.cancel(); release.complete(Unit)
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0L, count(database))
        }
    }
    @Test fun guardRejectedAfterInsertRollsBackTheRealSqliteTransaction() = runBlocking {
        fixture { database ->
            val records = repo(database, guard = { if (count(database) > 0) throw IllegalStateException("Controlled recovery boundary") })
            assertTrue(runCatching { records.create(book.id, book.sha256, text, anchor, PdfHighlightColor.GREEN, null) }.exceptionOrNull() is PdfAnnotationSourceException)
            assertEquals(0L, count(database))
        }
    }
    @Test fun recoveryGuardIsCheckedAfterWaitingForTheSharedFileMutexAndClosedRoomIsAFailure() = runBlocking {
        fixture { database ->
            val mutex = Mutex(locked = true); var denied = false; val proofs = AtomicInteger()
            val records = repo(database, PdfAnnotationOriginalSource { _, _, _ -> proofs.incrementAndGet(); Unit }, mutex) {
                check(!denied)
            }
            val pending = async { runCatching { records.create(book.id, book.sha256, text, anchor, PdfHighlightColor.GREEN, null) } }
            yield(); denied = true; mutex.unlock()
            assertTrue(pending.await().exceptionOrNull() is PdfAnnotationSourceException); assertEquals(0, proofs.get()); assertEquals(0L, count(database))
            denied = false; database.close()
            assertTrue(runCatching { records.observe(book.id, book.sha256).first() }.exceptionOrNull() is PdfAnnotationSourceException)
        }
    }
    @Test fun deliberatelyRemovingAnExistingAnnotationDoesNotRequireANowUnavailableNativeDecoder() = runBlocking {
        fixture { database ->
            val saved = repo(database).create(book.id, book.sha256, text, anchor, PdfHighlightColor.BLUE, "note")
            val records = repo(database, PdfAnnotationOriginalSource { _, _, _ -> error("Removal must not reopen native source") })
            records.remove(book.id, book.sha256, saved); assertEquals(0L, count(database))
        }
    }
    @Test fun corruptCurrentGeometryCannotBeAnEmptyListOrVerifiedPaintSuccess() = runBlocking {
        fixture { database ->
            val records = repo(database)
            val saved = records.create(book.id, book.sha256, text, anchor, PdfHighlightColor.GREEN, null)
            database.openHelper.writableDatabase.execSQL("UPDATE pdf_annotations SET anchorJson = '{}' WHERE id = ?", arrayOf(saved.id))
            assertTrue(runCatching { records.observe(book.id, book.sha256).first() }.exceptionOrNull() is PdfAnnotationSourceException)
            assertTrue(runCatching { records.verifyExisting(book.id, book.sha256, listOf(saved)) }.exceptionOrNull() is PdfAnnotationSourceException)
        }
    }
    @Test fun fractionalSqliteMetadataCannotBeTruncatedIntoSuccessfulNativeProofOrAnEmptyList() = runBlocking {
        for (statement in listOf("UPDATE books SET pageCount = 12.5", "UPDATE books SET byteSize = 100.25")) fixture { database ->
            database.openHelper.writableDatabase.execSQL(statement)
            var calls = 0
            val records = repo(database, PdfAnnotationOriginalSource { _, _, _ -> calls++ })
            assertTrue(runCatching { records.create(book.id, book.sha256, text, anchor, PdfHighlightColor.GREEN, null) }.exceptionOrNull() is PdfAnnotationSourceException)
            assertTrue(runCatching { records.observe(book.id, book.sha256).first() }.exceptionOrNull() is PdfAnnotationSourceException)
            assertEquals(0, calls); assertEquals(0L, count(database))
        }
    }
    @Test fun migrationEighteenToNineteenPreservesEveryPriorTableAndAddsEmptyPdfStorage() = runBlocking {
        val schema = JSONObject(requireNotNull(javaClass.classLoader).getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/18.json")!!.bufferedReader().use { it.readText() }).getJSONObject("database")
        val name = "pm-${UUID.randomUUID().toString().take(8)}.db"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val snapshots = linkedMapOf<String, Pair<String, List<List<String?>>>>()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sql ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                    sql.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    for (indexNumber in 0 until (indices?.length() ?: 0)) sql.execSQL(indices!!.getJSONObject(indexNumber).getString("createSql").replace('$' + "{TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) sql.execSQL(setup.getString(index))
                sql.execSQL("INSERT INTO books VALUES ('pdf','Kitap','Yazar','PDF','source.pdf','pdf.pdf',100,?,1,12)", arrayOf(book.sha256))
                sql.execSQL("INSERT INTO books VALUES ('epub','EPUB',NULL,'EPUB','source.epub','epub.epub',50,?,2,NULL)", arrayOf("b".repeat(64)))
                sql.execSQL("INSERT INTO book_notes VALUES ('note','pdf',?,'page note',10,3)", arrayOf(book.sha256))
                sql.execSQL("INSERT INTO book_pdf_view_preferences VALUES ('pdf',?,7,14,'WIDTH',1)", arrayOf(book.sha256))
                sql.execSQL("INSERT INTO saved_words VALUES ('word','pdf',?,'leaf','yaprak',6)", arrayOf(book.sha256))
                sql.execSQL("INSERT INTO epub_passages VALUES ('legacy','epub',?,1,'old.xhtml',?,0,3,'old',10,'BLUE','old note',3)", arrayOf("b".repeat(64), "c".repeat(64)))
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val fields = entity.getJSONArray("fields")
                    val columns = (0 until fields.length()).joinToString(",") { fields.getJSONObject(it).getString("columnName") }
                    val table = entity.getString("tableName")
                    snapshots[table] = columns to rows(sql.rawQuery("SELECT $columns FROM $table", null))
                }
                sql.version = 18
            }
            val database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build()
            try {
                snapshots.forEach { (table, before) -> assertEquals(table, before.second, rows(database.openHelper.writableDatabase.query("SELECT ${before.first} FROM $table"))) }
                assertEquals(0L, count(database)); repo(database).create(book.id, book.sha256, text, anchor, PdfHighlightColor.PINK, "new")
                assertEquals(1L, count(database)); assertEquals(26, database.openHelper.writableDatabase.version)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }
    private fun values(evidence: PdfSourceAnchor, selected: String) = ContentValues().apply {
        put("id", UUID.randomUUID().toString()); put("bookId", book.id); put("contentSha256", book.sha256)
        put("anchorVersion", 1); put("pageIndex", evidence.pageIndex); put("anchorJson", PdfAnchorCodec.encode(evidence))
        put("anchorKey", PdfAnchorCodec.key(evidence)); put("selectionKey", PdfAnchorCodec.selectionKey(evidence, selected)); put("text", selected); put("createdAt", 1L)
        put("highlightColor", "YELLOW"); putNull("attachedNote"); put("revision", 0L)
    }
    private fun rows(cursor: android.database.Cursor) = cursor.use { buildList {
        while (it.moveToNext()) add(List(it.columnCount) { column -> if (it.isNull(column)) null else it.getString(column) })
    } }
}
