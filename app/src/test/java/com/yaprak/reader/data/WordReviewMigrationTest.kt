package com.yaprak.reader.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WordReviewMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun old(name: String) {
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/19.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace('$' + "{TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO books VALUES ('book','Kitap',NULL,'EPUB','book.epub','book.epub',10,?,1,NULL)", arrayOf("a".repeat(64)))
            sql.execSQL("INSERT INTO saved_words VALUES ('legacy','book',?,'leaf','yaprak',7)", arrayOf("a".repeat(64)))
            sql.execSQL("INSERT INTO book_notes VALUES ('note','book',?,'Exact note',8,NULL)", arrayOf("a".repeat(64)))
            sql.version = 19
        }
    }
    @Test fun v19PreservesWordsAndNotesWithoutInventingLearning() = runBlocking {
        val name = "srm-${UUID.randomUUID().toString().take(8)}.db"
        try {
            old(name)
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build()
            try {
                val words = RoomSavedWordRepository(db)
                val word = words.observe("book", "a".repeat(64)).first().single()
                assertEquals("leaf", word.english); assertEquals("yaprak", word.turkish); assertEquals(7L, word.createdAt); assertEquals(0L, word.revision)
                assertEquals(26, db.openHelper.writableDatabase.version)
                val sql = db.openHelper.writableDatabase
                assertEquals("Exact note", sql.compileStatement("SELECT text FROM book_notes").simpleQueryForString())
                assertEquals(0L, sql.compileStatement("SELECT COUNT(*) FROM word_review_events").simpleQueryForLong())
                words.update("book", "a".repeat(64), word.id, "tree", "ağaç")
                assertEquals(1L, words.observe("book", "a".repeat(64)).first().single().revision)
                sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun missingMigrationRefusesToOpenAndLeavesOriginalWordsInPlace() {
        val name = "srn-${UUID.randomUUID().toString().take(8)}.db"
        try {
            old(name)
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            try { try { db.openHelper.writableDatabase; fail("Missing migration") } catch (_: IllegalStateException) {} }
            finally { db.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
                assertEquals(19, it.version); it.rawQuery("SELECT english FROM saved_words", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("leaf", cursor.getString(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }
}
