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
class BookReadingModePersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val book = Book("book", "Existing", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "hash", 1)

    @Test fun savedPagedAndScrollSurviveReopenAndOnlyExplicitInheritRemovesTheOwnedRow() = runBlocking {
        val name = "ba-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                val library = RoomLibraryRepository(db.books())
                library.add(book)
                library.add(book.copy(id = "txt", sha256 = "txt-hash", format = BookFormat.TXT,
                    originalName = "txt.txt", storageName = "txt.txt"))
                RoomBookThemeRepository(db).save(book.id, book.sha256, ReadingTheme.AMOLED)
                val repository = RoomBookReadingModeRepository(db)
                repository.save(book.id, book.sha256, BookReadingMode.PAGED)
                repository.save("txt", "txt-hash", BookReadingMode.SCROLL)
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                val repository = RoomBookReadingModeRepository(db)
                assertEquals(BookReadingMode.PAGED, repository.observe(book.id, book.sha256).first())
                assertEquals(BookReadingMode.SCROLL, repository.observe("txt", "txt-hash").first())
                repository.save(book.id, book.sha256, BookReadingMode.SCROLL)
                assertEquals(BookReadingMode.SCROLL, repository.observe(book.id, book.sha256).first())
                repository.save(book.id, book.sha256, null)
                assertNull(repository.observe(book.id, book.sha256).first())
                assertEquals(ReadingTheme.AMOLED, RoomBookThemeRepository(db).observe(book.id, book.sha256).first())
                assertEquals(BookReadingMode.SCROLL, repository.observe("txt", "txt-hash").first())
                assertEquals(1L, db.openHelper.writableDatabase.compileStatement(
                    "SELECT COUNT(*) FROM book_epub_reading_mode_overrides").simpleQueryForLong())
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    @Test fun changedHashMissingBookFixedFormatsAndCascadeCannotReuseAnotherChoice() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val library = RoomLibraryRepository(db.books())
            library.add(book)
            for (format in listOf(BookFormat.PDF, BookFormat.CBZ)) {
                library.add(book.copy(id = format.name, sha256 = format.name + "-hash", format = format,
                    storageName = format.name, pageCount = 2))
            }
            val repository = RoomBookReadingModeRepository(db)
            repository.save(book.id, book.sha256, BookReadingMode.SCROLL)
            for ((id, hash) in listOf("book" to "changed", "missing" to "hash", "PDF" to "PDF-hash", "CBZ" to "CBZ-hash")) {
                assertNull(repository.observe(id, hash).first())
                for (choice in listOf(null, BookReadingMode.PAGED)) {
                    assertNotNull(runCatching { repository.save(id, hash, choice) }.exceptionOrNull())
                }
            }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE books SET sha256 = 'changed' WHERE id = 'book'")
            assertNull(repository.observe(book.id, book.sha256).first())
            assertNull(repository.observe(book.id, "changed").first())
            sql.execSQL("DELETE FROM books WHERE id = 'book'")
            assertEquals(0L, sql.compileStatement("SELECT COUNT(*) FROM book_epub_reading_mode_overrides").simpleQueryForLong())
        }
    }

    @Test fun failedReplacementKeepsSavedChoiceAndUnknownEnumIsReportedWithoutRepair() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(book)
            val repository = RoomBookReadingModeRepository(db)
            repository.save(book.id, book.sha256, BookReadingMode.PAGED)
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER refuse_mode BEFORE INSERT ON book_epub_reading_mode_overrides BEGIN SELECT RAISE(ABORT, 'controlled'); END")
            assertNotNull(runCatching { repository.save(book.id, book.sha256, BookReadingMode.SCROLL) }.exceptionOrNull())
            assertEquals(BookReadingMode.PAGED, repository.observe(book.id, book.sha256).first())
            sql.execSQL("DROP TRIGGER refuse_mode")
            sql.execSQL("UPDATE book_epub_reading_mode_overrides SET mode = 'BROKEN'")
            assertNotNull(runCatching { repository.observe(book.id, book.sha256).first() }.exceptionOrNull())
            assertEquals("BROKEN", sql.compileStatement("SELECT mode FROM book_epub_reading_mode_overrides").simpleQueryForString())
        }
    }

    private fun rows(cursor: Cursor): List<List<String?>> = cursor.use {
        buildList { while (it.moveToNext()) add(List(it.columnCount) { column -> if (it.isNull(column)) null else it.getString(column) }) }
    }

    /** The exported old schema owns every table, column, index and identity, independently of new entities. */
    private fun version23(name: String): Map<String, List<List<String?>>> {
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/23.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        return SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            val entities = schema.getJSONArray("entities")
            val tables = mutableListOf<String>()
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                tables += table
                sql.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) sql.execSQL(indices.getJSONObject(i).getString("createSql").replace('$' + "{TABLE_NAME}", table))
                val fields = entity.getJSONArray("fields")
                val columns = mutableListOf<String>()
                val values = mutableListOf<Any?>()
                for (i in 0 until fields.length()) {
                    val field = fields.getJSONObject(i)
                    val column = field.getString("columnName")
                    columns += column
                    values += when {
                        table == "books" -> when (column) {
                            "id" -> "book"; "title" -> "Existing"; "authors", "pageCount" -> null
                            "format" -> "EPUB"; "originalName", "storageName" -> "book.epub"
                            "byteSize" -> 100L; "sha256" -> "hash"; "importedAt" -> 1L
                            else -> error("Unexpected old book column")
                        }
                        column == "bookId" -> "book"
                        column == "wordId" || (table == "saved_words" && column == "id") -> "word"
                        table == "book_epub_text_alignment_overrides" && column == "alignment" -> "JUSTIFY"
                        column == "contentSha256" -> "hash"
                        field.getString("affinity") == "INTEGER" -> 800L + i
                        else -> "$table/$column Türkçe 🌿"
                    }
                }
                sql.execSQL("INSERT INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})", values.toTypedArray())
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) sql.execSQL(setup.getString(index))
            sql.version = 23
            tables.associateWith { rows(sql.rawQuery("SELECT * FROM $it", null)) }
        }
    }

    @Test fun actual23To24MigrationPreservesEveryOldTableCellAndForeignKeyWithNoInventedChoice() = runBlocking {
        val name = "bam-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            val baseline = version23(name)
            assertEquals(23, baseline.size)
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { db ->
                    val sql = db.openHelper.writableDatabase
                    assertEquals(26, sql.version)
                    baseline.forEach { (table, expected) -> assertEquals(table, expected, rows(sql.query("SELECT * FROM $table"))) }
                    sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    val repository = RoomBookReadingModeRepository(db)
                    assertEquals(ReadingTextAlignment.JUSTIFY, RoomBookTextAlignmentRepository(db).observe(book.id, book.sha256).first())
                    assertNull(repository.observe(book.id, book.sha256).first())
                    repository.save(book.id, book.sha256, BookReadingMode.PAGED)
                }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                assertEquals(BookReadingMode.PAGED, RoomBookReadingModeRepository(db).observe(book.id, book.sha256).first())
                baseline.forEach { (table, expected) -> assertEquals(table, expected, rows(db.openHelper.writableDatabase.query("SELECT * FROM $table"))) }
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    @Test fun missingMigrationRefusesWithoutChangingAnyVersion23Rows() {
        val name = "bar-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            val baseline = version23(name)
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            try { assertThrows(IllegalStateException::class.java) { db.openHelper.writableDatabase } }
            finally { db.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
                assertEquals(23, sql.version)
                baseline.forEach { (table, expected) -> assertEquals(table, expected, rows(sql.rawQuery("SELECT * FROM $table", null))) }
            }
        } finally { context.deleteDatabase(name) }
    }
}
