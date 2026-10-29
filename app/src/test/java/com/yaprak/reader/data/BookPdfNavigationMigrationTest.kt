package com.yaprak.reader.data

import android.app.Application
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
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

/** The frozen exported v25 schema, rather than current entities, owns the old fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookPdfNavigationMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val hash = "a".repeat(64)
    private fun rows(cursor: Cursor): List<List<String?>> = cursor.use {
        buildList { while (it.moveToNext()) add(List(it.columnCount) { column -> if (it.isNull(column)) null else it.getString(column) }) }
    }
    private fun version25(name: String): Map<String, List<List<String?>>> {
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/25.json")!!
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
                            "id" -> "book"; "title" -> "Existing PDF"; "authors", "pageCount" -> null
                            "format" -> "PDF"; "originalName", "storageName" -> "book.pdf"
                            "byteSize" -> 100L; "sha256" -> hash; "importedAt" -> 1L
                            else -> error("Unexpected old book column")
                        }
                        column == "bookId" -> "book"
                        column == "wordId" || (table == "saved_words" && column == "id") -> "word"
                        column == "contentSha256" -> hash
                        table == "book_pdf_view_preferences" -> when (column) {
                            "horizontalPercent" -> 10; "verticalPercent" -> 20
                            "fitMode" -> "WIDTH"; "doublePagePreferred" -> 1
                            else -> error("Unexpected old PDF column")
                        }
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
            sql.version = 25
            tables.associateWith { rows(sql.rawQuery("SELECT * FROM $it", null)) }
        }
    }
    @Test fun actual25To26PreservesEveryOldCellAndDoesNotInferANavigationChoice() = runBlocking {
        val name = "pn-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            val baseline = version25(name)
            assertEquals(25, baseline.size)
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_25_26).build().useDatabase { db ->
                    val sql = db.openHelper.writableDatabase
                    assertEquals(26, sql.version)
                    baseline.forEach { (table, expected) -> assertEquals(table, expected, rows(sql.query("SELECT * FROM $table"))) }
                    sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    assertEquals(0L, sql.compileStatement("SELECT COUNT(*) FROM book_pdf_navigation_preferences").simpleQueryForLong())
                    assertEquals(BookPdfViewPreferences(10, 20, PdfFitPreference.WIDTH, true),
                        RoomBookPdfViewPreferencesRepository(db).observe("book", hash).first())
                    sql.query("PRAGMA foreign_key_list(book_pdf_navigation_preferences)").use { cursor ->
                        assertTrue(cursor.moveToFirst()); assertEquals("books", cursor.getString(2))
                        assertEquals("bookId", cursor.getString(3)); assertEquals("id", cursor.getString(4))
                        assertEquals("CASCADE", cursor.getString(6)); assertFalse(cursor.moveToNext())
                    }
                }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                baseline.forEach { (table, expected) -> assertEquals(table, expected, rows(db.openHelper.writableDatabase.query("SELECT * FROM $table"))) }
                assertEquals(PdfNavigationMode.PAGED, RoomBookPdfViewPreferencesRepository(db).observe("book", hash).first().navigationMode)
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }
    @Test fun missingMigrationRefusesWithoutChangingAnyOld25Cell() {
        val name = "pnm-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            val baseline = version25(name)
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            try { assertThrows(IllegalStateException::class.java) { db.openHelper.writableDatabase } }
            finally { db.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
                assertEquals(25, sql.version)
                baseline.forEach { (table, expected) -> assertEquals(table, expected, rows(sql.rawQuery("SELECT * FROM $table", null))) }
            }
        } finally { context.deleteDatabase(name) }
    }
}
