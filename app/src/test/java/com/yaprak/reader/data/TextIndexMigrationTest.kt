package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.search.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TextIndexMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val generation = "84c16826-234a-4ccf-961e-d0590179a2e8"
    private fun versionOne(name: String) {
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.search.TextIndexDatabase/1.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                db.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", entity.getString("tableName")))
                entity.optJSONArray("indices")?.let { indices ->
                    for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql")
                        .replace('$' + "{TABLE_NAME}", entity.getString("tableName")))
                }
                entity.optJSONArray("contentSyncTriggers")?.let { triggers ->
                    for (j in 0 until triggers.length()) db.execSQL(triggers.getString(j))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO text_index_books VALUES ('legacy',?,1,?,'READY',1,400)", arrayOf("a".repeat(64), generation))
            db.execSQL("INSERT INTO text_index_chunks VALUES (41,'legacy',?,?,0,0,'OPS/one.xhtml',?,NULL,'İSTANBUL legacy','istanbul legacy')",
                arrayOf("a".repeat(64), generation, "b".repeat(64)))
            db.version = 1
        }
    }

    @Test fun `schema one migration preserves EPUB FTS and typed PDF rows`() = runBlocking {
        val name = "idx-${UUID.randomUUID().toString().take(8)}.db"; versionOne(name)
        val db = Room.databaseBuilder(context, TextIndexDatabase::class.java, name)
            .addMigrations(TextIndexDatabase.MIGRATION_1_2).build()
        try {
            val dao = db.index(); val legacy = dao.books().single()
            assertEquals("EPUB", legacy.sourceFormat); assertEquals(400L, legacy.stringBytes)
            val old = dao.find("\"istanbul\"", listOf("legacy"), 10).single()
            assertEquals(41L, old.id); assertEquals("EPUB", old.sourceFormat); assertNull(old.pdfPageIndex)
            assertEquals("İSTANBUL legacy", old.text); assertEquals(generation, old.generation)
            db.openHelper.writableDatabase.execSQL("UPDATE text_index_chunks SET text = 'Updated marker', searchText = 'updated marker' WHERE rowid = 41")
            assertEquals(0, dao.count("\"legacy\"", listOf("legacy")))
            assertEquals(1, dao.count("\"marker\"", listOf("legacy")))
            dao.insertBook(TextIndexBookEntity("pdf", "c".repeat(64), 1, generation, "READY", 1, 400, "PDF"))
            dao.insertChunks(listOf(TextIndexChunkEntity(bookId = "pdf", contentSha256 = "c".repeat(64),
                generation = generation, readingOrderIndex = 2, chunkIndex = 0, resourceHref = "", resourceSha256 = "d".repeat(64),
                chapterTitle = null, text = "PDF marker", searchText = "pdf marker", sourceFormat = "PDF", pdfPageIndex = 2)))
            val page = dao.find("\"marker\"", listOf("pdf"), 10).single()
            assertEquals(2, page.pdfPageIndex); assertEquals("PDF", page.sourceFormat)
            dao.deleteBook("legacy")
            assertEquals(0, dao.count("\"marker\"", listOf("legacy")))
            assertEquals(1, dao.count("\"marker\"", listOf("pdf")))
            assertEquals(2, db.openHelper.writableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun `missing migration fails without destroying the old EPUB cache`() {
        val name = "idx-${UUID.randomUUID().toString().take(8)}.db"; versionOne(name)
        val db = Room.databaseBuilder(context, TextIndexDatabase::class.java, name).build()
        try {
            assertTrue(runCatching { db.openHelper.writableDatabase }.isFailure)
            db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { old ->
                assertEquals(1, old.version)
                assertTrue(old.rawQuery("SELECT text FROM text_index_chunks WHERE rowid=41", null).use {
                    it.moveToFirst() && it.getString(0) == "İSTANBUL legacy"
                })
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
