package com.yaprak.reader.data

import android.app.Application
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The frozen exported v26 schema owns the old fixture; 26 -> 27 only adds reading_page_receipts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadingPageMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val hash = "a".repeat(64)
    private fun rows(cursor: Cursor): List<List<String?>> = cursor.use {
        buildList { while (it.moveToNext()) add(List(it.columnCount) { column -> if (it.isNull(column)) null else it.getString(column) }) }
    }
    private fun version26(name: String): Map<String, List<List<String?>>> {
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/26.json")!!
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
                            "id" -> "book"; "title" -> "Existing PDF"; "authors" -> null; "pageCount" -> 12L
                            "format" -> "PDF"; "originalName", "storageName" -> "book.pdf"
                            "byteSize" -> 100L; "sha256" -> hash; "importedAt" -> 1L
                            else -> error("Unexpected old book column $column")
                        }
                        column == "bookId" -> "book"
                        column == "wordId" || (table == "saved_words" && column == "id") -> "word"
                        column == "contentSha256" -> hash
                        table == "book_pdf_view_preferences" -> when (column) {
                            "horizontalPercent" -> 10; "verticalPercent" -> 20
                            "fitMode" -> "WIDTH"; "doublePagePreferred" -> 1
                            else -> error("Unexpected old PDF column")
                        }
                        table == "book_pdf_navigation_preferences" && column == "mode" -> "PAGED"
                        table == "book_epub_text_alignment_overrides" && column == "alignment" -> "JUSTIFY"
                        table == "book_epub_reading_mode_overrides" && column == "mode" -> "SCROLL"
                        table == "book_cbz_view_preferences" && column == "doublePagePreferred" -> 1
                        field.getString("affinity") == "INTEGER" -> 800L + i
                        else -> "$table/$column Türkçe 🌿"
                    }
                }
                sql.execSQL("INSERT INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})", values.toTypedArray())
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) sql.execSQL(setup.getString(index))
            sql.version = 26
            tables.associateWith { rows(sql.rawQuery("SELECT * FROM $it", null)) }
        }
    }

    @Test fun actual26To27PreservesEveryOldCellAndStartsWithNoPageRecords() = runBlocking {
        val name = "rp-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            val baseline = version26(name)
            assertEquals(26, baseline.size)
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_26_27, LibraryDatabase.MIGRATION_27_28).build().useDatabase { db ->
                    val sql = db.openHelper.writableDatabase
                    assertEquals(28, sql.version)
                    baseline.forEach { (table, expected) -> assertEquals(table, expected, rows(sql.query("SELECT * FROM $table"))) }
                    sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    // Old reading time keeps its value; its page count stays unknown rather than invented.
                    assertEquals(0L, sql.compileStatement("SELECT COUNT(*) FROM reading_page_receipts").simpleQueryForLong())
                    sql.query("PRAGMA foreign_key_list(reading_page_receipts)").use { cursor ->
                        assertTrue(cursor.moveToFirst()); assertEquals("books", cursor.getString(2))
                        assertEquals("bookId", cursor.getString(3)); assertEquals("CASCADE", cursor.getString(6))
                        assertFalse(cursor.moveToNext())
                    }
                }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    @Test fun missingMigrationRefusesWithoutChangingAnyOld26Cell() {
        val name = "rpm-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            val baseline = version26(name)
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            try { assertThrows(IllegalStateException::class.java) { db.openHelper.writableDatabase } }
            finally { db.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
                assertEquals(26, sql.version)
                baseline.forEach { (table, expected) -> assertEquals(table, expected, rows(sql.rawQuery("SELECT * FROM $table", null))) }
            }
        } finally { context.deleteDatabase(name) }
    }
}
