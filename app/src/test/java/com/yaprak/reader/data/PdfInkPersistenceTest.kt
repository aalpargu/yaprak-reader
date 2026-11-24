package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.domain.pdfink.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room/SQLite: highlighter strokes persist exactly, follow their book and survive 27 -> 28. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PdfInkPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("pdf", "Kitap", "Yazar", BookFormat.PDF, "source.pdf", "pdf.pdf", 100, "a".repeat(64), 1, pageCount = 12)
    private fun stroke(id: String = UUID.randomUUID().toString(), page: Int = 3, mode: PdfInkMode = PdfInkMode.ALIGNED) =
        PdfInkStroke(id, page, PdfHighlightColor.PINK, mode, listOf(
            PdfInkSegment(3030, listOf(PdfInkPoint(10_000, 12_000), PdfInkPoint(70_000, 12_000))),
            PdfInkSegment(1136, listOf(PdfInkPoint(10_000, 31_000), PdfInkPoint(45_500, 31_000)))), 1_700_000_000_000)
    private fun count(database: LibraryDatabase) = database.openHelper.writableDatabase
        .compileStatement("SELECT COUNT(*) FROM pdf_ink_strokes").simpleQueryForLong()

    @Test fun strokesRoundTripExactlyAcrossReopenAndCanBeRemoved() = runBlocking {
        val name = "ink-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            database.books().insert(book.toEntity())
            val first = stroke(); val second = stroke(page = 0, mode = PdfInkMode.FREEHAND).copy(createdAt = 1_700_000_000_001)
            RoomPdfInkRepository(database).add(book.id, book.sha256, first)
            RoomPdfInkRepository(database).add(book.id, book.sha256, second)
            database.close()
            database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            val records = RoomPdfInkRepository(database)
            assertEquals(listOf(first, second), records.observe(book.id, book.sha256).first())
            // Another content hash (replaced file) sees nothing.
            assertTrue(records.observe(book.id, "b".repeat(64)).first().isEmpty())
            records.remove(book.id, book.sha256, first.id)
            assertEquals(listOf(second), records.observe(book.id, book.sha256).first())
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun deletingTheBookCascadesItsInk() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            database.books().insert(book.toEntity())
            RoomPdfInkRepository(database).add(book.id, book.sha256, stroke())
            assertEquals(1L, count(database))
            database.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = ?", arrayOf(book.id))
            assertEquals(0L, count(database))
        } finally { database.close() }
    }

    @Test fun writesAreCheckedAgainstTheBookAndTheLimit() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            database.books().insert(book.toEntity())
            val records = RoomPdfInkRepository(database)
            assertTrue(runCatching { records.add(book.id, "b".repeat(64), stroke()) }.isFailure)
            assertTrue(runCatching { records.add("missing", book.sha256, stroke()) }.isFailure)
            assertTrue(runCatching { records.add(book.id, book.sha256, stroke(page = 12)) }.isFailure)
            assertEquals(0L, count(database))
            val sql = database.openHelper.writableDatabase
            val geometry = PdfInkGeometryCodec.encode(stroke().segments)
            repeat(MAX_PDF_INK_STROKES_PER_BOOK) {
                sql.execSQL("INSERT INTO pdf_ink_strokes VALUES (?,?,?,?,?,?,?,?)",
                    arrayOf<Any>("x$it", book.id, book.sha256, 0, "YELLOW", "ALIGNED", geometry, it.toLong()))
            }
            assertTrue(runCatching { records.add(book.id, book.sha256, stroke()) }.exceptionOrNull() is PdfInkLimitException)
            // A corrupted row is skipped, never painted from guessed geometry.
            sql.execSQL("UPDATE pdf_ink_strokes SET geometry = '1;0,0,0' WHERE id = 'x0'")
            assertEquals(MAX_PDF_INK_STROKES_PER_BOOK - 1, records.observe(book.id, book.sha256).first().size)
        } finally { database.close() }
    }

    @Test fun accessGuardRunsBeforeReadsAndWrites() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            database.books().insert(book.toEntity())
            val guarded = RoomPdfInkRepository(database) { error("restore in progress") }
            assertTrue(runCatching { guarded.add(book.id, book.sha256, stroke()) }.isFailure)
            assertTrue(runCatching { guarded.observe(book.id, book.sha256) }.isFailure)
            assertEquals(0L, count(database))
        } finally { database.close() }
    }

    @Test fun migrationTwentySevenToTwentyEightKeepsEveryRowAndAddsEmptyInkStorage() = runBlocking {
        val schema = JSONObject(requireNotNull(javaClass.classLoader).getResourceAsStream(
            "com.yaprak.reader.data.persistence.LibraryDatabase/27.json")!!.bufferedReader().use { it.readText() }).getJSONObject("database")
        val name = "ink-m-${UUID.randomUUID().toString().take(8)}.db"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val snapshots = linkedMapOf<String, Pair<String, List<List<String?>>>>()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sql ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                    sql.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    for (indexNumber in 0 until (indices?.length() ?: 0))
                        sql.execSQL(indices!!.getJSONObject(indexNumber).getString("createSql").replace('$' + "{TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) sql.execSQL(setup.getString(index))
                sql.execSQL("INSERT INTO books VALUES ('pdf','Kitap','Yazar','PDF','source.pdf','pdf.pdf',100,?,1,12)", arrayOf(book.sha256))
                sql.execSQL("INSERT INTO books VALUES ('epub','EPUB',NULL,'EPUB','source.epub','epub.epub',50,?,2,NULL)", arrayOf("b".repeat(64)))
                sql.execSQL("INSERT INTO book_notes VALUES ('note','pdf',?,'page note',10,3)", arrayOf(book.sha256))
                sql.execSQL("INSERT INTO book_pdf_view_preferences VALUES ('pdf',?,7,14,'WIDTH',1)", arrayOf(book.sha256))
                sql.execSQL("INSERT INTO reading_page_receipts VALUES ('r1','pdf',?,'2026-11-20',18)", arrayOf(book.sha256))
                sql.execSQL("INSERT INTO reading_duration_receipts VALUES ('r1','pdf',?,'2026-11-20','Europe/Istanbul',1,2,1)", arrayOf(book.sha256))
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val fields = entity.getJSONArray("fields")
                    val columns = (0 until fields.length()).joinToString(",") { fields.getJSONObject(it).getString("columnName") }
                    val table = entity.getString("tableName")
                    snapshots[table] = columns to rows(sql.rawQuery("SELECT $columns FROM $table", null))
                }
                sql.version = 27
            }
            val database = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_27_28).build()
            try {
                val sql = database.openHelper.writableDatabase
                snapshots.forEach { (table, before) -> assertEquals(table, before.second, rows(sql.query("SELECT ${before.first} FROM $table"))) }
                assertTrue(snapshots.getValue("reading_page_receipts").second.isNotEmpty())
                assertEquals(0L, count(database))
                sql.query("PRAGMA foreign_key_list(pdf_ink_strokes)").use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals("books", cursor.getString(2)); assertEquals("CASCADE", cursor.getString(6))
                }
                val saved = stroke()
                RoomPdfInkRepository(database).add(book.id, book.sha256, saved)
                assertEquals(listOf(saved), RoomPdfInkRepository(database).observe(book.id, book.sha256).first())
                assertEquals(28, sql.version)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun rows(cursor: android.database.Cursor) = cursor.use { buildList {
        while (it.moveToNext()) add(List(it.columnCount) { column -> if (it.isNull(column)) null else it.getString(column) })
    } }
}
