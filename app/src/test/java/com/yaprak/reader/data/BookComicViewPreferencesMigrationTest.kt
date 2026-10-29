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
class BookComicViewPreferencesMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val book = Book("book", "Existing", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "hash", 1)

    private fun rows(cursor: Cursor): List<List<String?>> = cursor.use {
        buildList { while (it.moveToNext()) add(List(it.columnCount) { column -> if (it.isNull(column)) null else it.getString(column) }) }
    }

    /** The exported old schema owns every table, column, index and identity, independently of new entities. */
    private fun version24(name: String): Map<String, List<List<String?>>> {
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/24.json")!!
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
                        table == "book_epub_reading_mode_overrides" && column == "mode" -> "SCROLL"
                        column == "contentSha256" -> "hash"
                        field.getString("affinity") == "INTEGER" -> 800L + i
                        else -> "$table/$column Türkçe 🌿"
                    }
                }
                sql.execSQL("INSERT INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})", values.toTypedArray())
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) sql.execSQL(setup.getString(index))
            sql.version = 24
            tables.associateWith { rows(sql.rawQuery("SELECT * FROM $it", null)) }
        }
    }

    @Test fun actual24To26PreservesEveryOldCellAndExistingAlignmentAndReadingMode() = runBlocking {
        val name = "cm-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            val baseline = version24(name)
            assertEquals(24, baseline.size)
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { db ->
                    val sql = db.openHelper.writableDatabase
                    assertEquals(26, sql.version)
                    baseline.forEach { (table, expected) -> assertEquals(table, expected, rows(sql.query("SELECT * FROM $table"))) }
                    sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    assertEquals(ReadingTextAlignment.JUSTIFY, RoomBookTextAlignmentRepository(db).observe(book.id, book.sha256).first())
                    assertEquals(BookReadingMode.SCROLL, RoomBookReadingModeRepository(db).observe(book.id, book.sha256).first())
                    assertEquals(0L, sql.compileStatement("SELECT COUNT(*) FROM book_cbz_view_preferences").simpleQueryForLong())
                    sql.query("PRAGMA foreign_key_list(book_cbz_view_preferences)").use { cursor ->
                        assertTrue(cursor.moveToFirst()); assertEquals("books", cursor.getString(2))
                        assertEquals("bookId", cursor.getString(3)); assertEquals("id", cursor.getString(4))
                        assertEquals("CASCADE", cursor.getString(6)); assertFalse(cursor.moveToNext())
                    }
                }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                baseline.forEach { (table, expected) -> assertEquals(table, expected, rows(db.openHelper.writableDatabase.query("SELECT * FROM $table"))) }
                assertEquals(BookReadingMode.SCROLL, RoomBookReadingModeRepository(db).observe(book.id, book.sha256).first())
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }
    @Test fun missingMigrationRefusesWithoutModifyingAnyOld24Cell() {
        val name = "cmm-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            val baseline = version24(name)
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            try { assertThrows(IllegalStateException::class.java) { db.openHelper.writableDatabase } }
            finally { db.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
                assertEquals(24, sql.version)
                baseline.forEach { (table, expected) -> assertEquals(table, expected, rows(sql.rawQuery("SELECT * FROM $table", null))) }
            }
        } finally { context.deleteDatabase(name) }
    }
}
