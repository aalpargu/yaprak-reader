package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.data.persistence.RoomSavedWordRepository
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
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
@Config(sdk = [35], application = Application::class)
class SavedWordPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val epub = Book("epub", "Existing", null, BookFormat.EPUB,
        "book.epub", "book.epub", 100, "epub-hash", 1)
    private val pdf = Book("pdf", "PDF", null, BookFormat.PDF,
        "book.pdf", "book.pdf", 100, "pdf-hash", 2, 10)

    @Test fun `v8 migration retains old data`() = runBlocking {
        val name = "wm-${UUID.randomUUID().toString().take(8)}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!
            .getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/8.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(j).getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                db.execSQL("INSERT INTO books VALUES ('epub','Existing',NULL,'EPUB','book.epub','book.epub',100,'epub-hash',1,NULL)")
                db.execSQL("INSERT INTO reading_progress VALUES ('epub','epub-hash',1,'saved','one.xhtml',5)")
                db.execSQL("INSERT INTO observed_text VALUES ('epub','epub-hash',1,'one.xhtml','0/0',0,5,?)", arrayOf("a".repeat(64)))
                db.execSQL("INSERT INTO bookmarks VALUES ('bookmark','epub','epub-hash','location-key','First','saved','one.xhtml',1,6)")
                db.execSQL("INSERT INTO book_epub_theme_overrides VALUES ('epub','epub-hash','SEPIA')")
                db.execSQL("INSERT INTO book_epub_font_size_overrides VALUES ('epub','epub-hash',125)")
                db.execSQL("INSERT INTO book_epub_font_overrides VALUES ('epub','epub-hash','SERIF')")
                db.execSQL("INSERT INTO book_epub_margin_overrides VALUES ('epub','epub-hash',150)")
                db.execSQL("INSERT INTO book_epub_line_spacing_overrides VALUES ('epub','epub-hash',140)")
                db.version = 8
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_8_9, LibraryDatabase.MIGRATION_9_10, LibraryDatabase.MIGRATION_10_11, LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { db ->
                    assertOldRows(db)
                    assertTrue(RoomSavedWordRepository(db).observe(epub.id, epub.sha256).first().isEmpty())
                    RoomSavedWordRepository(db).add(epub.id, epub.sha256, "leaf", "yaprak")
                }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                assertOldRows(db)
                assertEquals("yaprak", RoomSavedWordRepository(db)
                    .observe(epub.id, epub.sha256).first().single().turkish)
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    private fun assertOldRows(db: LibraryDatabase) {
        val sqlite = db.openHelper.writableDatabase
        listOf("books", "reading_progress", "observed_text", "bookmarks",
            "book_epub_theme_overrides", "book_epub_font_size_overrides",
            "book_epub_font_overrides", "book_epub_margin_overrides",
            "book_epub_line_spacing_overrides").forEach { table ->
            assertEquals(table, 1L, sqlite.compileStatement("SELECT count(*) FROM $table").simpleQueryForLong())
        }
        assertEquals("Existing", sqlite.compileStatement("SELECT title FROM books WHERE id='epub'").simpleQueryForString())
        assertEquals("saved", sqlite.compileStatement("SELECT locatorJson FROM reading_progress WHERE bookId='epub'").simpleQueryForString())
        assertEquals("First", sqlite.compileStatement("SELECT label FROM bookmarks WHERE id='bookmark'").simpleQueryForString())
        assertEquals(140L, sqlite.compileStatement("SELECT lineHeightPercent FROM book_epub_line_spacing_overrides WHERE bookId='epub'").simpleQueryForLong())
    }

    @Test fun `words persist by book`() = runBlocking {
        val name = "wp-${UUID.randomUUID().toString().take(8)}.db"
        try {
            fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            open().useDatabase { db ->
                val library = RoomLibraryRepository(db.books())
                library.add(epub)
                library.add(pdf)
                val words = RoomSavedWordRepository(db)
                words.add(epub.id, epub.sha256, "  leaf  ", "  yaprak  ")
                words.add(epub.id, epub.sha256, "leaf", "sayfa")
                words.add(pdf.id, pdf.sha256, " page ", " sayfa ")
                assertEquals(listOf("sayfa", "yaprak"), words.observe(epub.id, epub.sha256)
                    .first().map { it.turkish }.sorted())
                assertEquals("page", words.observe(pdf.id, pdf.sha256).first().single().english)
                assertEquals(0L, db.openHelper.writableDatabase.compileStatement(
                    "SELECT count(*) FROM reading_progress").simpleQueryForLong())
                assertEquals(0L, db.openHelper.writableDatabase.compileStatement(
                    "SELECT count(*) FROM observed_text").simpleQueryForLong())
            }
            open().useDatabase { db ->
                val words = RoomSavedWordRepository(db)
                assertEquals(2, words.observe(epub.id, epub.sha256).first().size)
                assertEquals(1, words.observe(pdf.id, pdf.sha256).first().size)
                val saved = words.observe(epub.id, epub.sha256).first().first()
                assertNotNull(runCatching { words.remove(pdf.id, pdf.sha256, saved.id) }.exceptionOrNull())
                assertNotNull(runCatching { words.remove(epub.id, "wrong-hash", saved.id) }.exceptionOrNull())
                assertEquals(2, words.observe(epub.id, epub.sha256).first().size)
                words.remove(epub.id, epub.sha256, saved.id)
                assertEquals(1, words.observe(epub.id, epub.sha256).first().size)
                assertEquals(1, words.observe(pdf.id, pdf.sha256).first().size)
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun `invalid input or changed book content cannot create or remove words`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(epub)
            val words = RoomSavedWordRepository(db)
            val invalid = listOf("", "   ", "a".repeat(101), "one\ntwo", "\nleaf", "leaf\r")
            invalid.forEach { english ->
                assertNotNull(runCatching { words.add(epub.id, epub.sha256, english, "anlam") }.exceptionOrNull())
            }
            listOf("", "  ", "a".repeat(201), "bir\niki", "\nyaprak", "yaprak\r").forEach { turkish ->
                assertNotNull(runCatching { words.add(epub.id, epub.sha256, "word", turkish) }.exceptionOrNull())
            }
            assertNotNull(runCatching { words.add(epub.id, "wrong", "word", "anlam") }.exceptionOrNull())
            assertNotNull(runCatching { words.add("missing", epub.sha256, "word", "anlam") }.exceptionOrNull())
            assertTrue(words.observe(epub.id, epub.sha256).first().isEmpty())
            words.add(epub.id, epub.sha256, "word", "anlam")
            val saved = words.observe(epub.id, epub.sha256).first().single()
            db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = 'changed' WHERE id = 'epub'")
            assertTrue(words.observe(epub.id, epub.sha256).first().isEmpty())
            assertTrue(words.observe(epub.id, "changed").first().isEmpty())
            assertNotNull(runCatching { words.remove(epub.id, epub.sha256, saved.id) }.exceptionOrNull())
            assertNotNull(runCatching { words.add(epub.id, epub.sha256, "another", "başka") }.exceptionOrNull())
            assertEquals(1L, db.openHelper.writableDatabase.compileStatement(
                "SELECT count(*) FROM saved_words").simpleQueryForLong())
            db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = 'epub'")
            assertEquals(0L, db.openHelper.writableDatabase.compileStatement(
                "SELECT count(*) FROM saved_words").simpleQueryForLong())
        }
    }

    @Test fun `edit survives reopen`() = runBlocking {
        val name = "we-${UUID.randomUUID().toString().take(8)}.db"
        lateinit var original: com.yaprak.reader.domain.vocabulary.SavedWord
        lateinit var untouched: com.yaprak.reader.domain.vocabulary.SavedWord
        lateinit var otherBook: com.yaprak.reader.domain.vocabulary.SavedWord
        try {
            fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            open().useDatabase { db ->
                val library = RoomLibraryRepository(db.books())
                library.add(epub)
                library.add(pdf)
                val words = RoomSavedWordRepository(db)
                words.add(epub.id, epub.sha256, "leaf", "yaprak")
                words.add(epub.id, epub.sha256, "page", "sayfa")
                words.add(pdf.id, pdf.sha256, "page", "sayfa")
                original = words.observe(epub.id, epub.sha256).first().single { it.english == "leaf" }
                untouched = words.observe(epub.id, epub.sha256).first().single { it.english == "page" }
                otherBook = words.observe(pdf.id, pdf.sha256).first().single()
                words.update(epub.id, epub.sha256, original.id, "  foliage  ", "  yapraklar  ")
            }
            open().useDatabase { db ->
                val words = RoomSavedWordRepository(db)
                val edited = words.observe(epub.id, epub.sha256).first().single { it.id == original.id }
                assertEquals(original.id, edited.id)
                assertEquals(original.createdAt, edited.createdAt)
                assertEquals("foliage", edited.english)
                assertEquals("yapraklar", edited.turkish)
                assertEquals(untouched, words.observe(epub.id, epub.sha256).first().single { it.id == untouched.id })
                assertEquals(otherBook, words.observe(pdf.id, pdf.sha256).first().single())
                assertEquals(0L, db.openHelper.writableDatabase.compileStatement(
                    "SELECT count(*) FROM reading_progress").simpleQueryForLong())
                assertEquals(0L, db.openHelper.writableDatabase.compileStatement(
                    "SELECT count(*) FROM observed_text").simpleQueryForLong())
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun `failed edit leaves saved text unchanged and a retry can succeed`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val library = RoomLibraryRepository(db.books())
            library.add(epub)
            library.add(pdf)
            val words = RoomSavedWordRepository(db)
            words.add(epub.id, epub.sha256, "leaf", "yaprak")
            val saved = words.observe(epub.id, epub.sha256).first().single()
            val invalidEnglish = listOf("", "   ", "a".repeat(101), "two\nlines", "\nleaf", "leaf\r")
            invalidEnglish.forEach { english ->
                assertNotNull(runCatching { words.update(epub.id, epub.sha256, saved.id, english, "anlam") }.exceptionOrNull())
            }
            val invalidTurkish = listOf("", "   ", "a".repeat(201), "iki\nsatır", "\nyaprak", "yaprak\r")
            invalidTurkish.forEach { turkish ->
                assertNotNull(runCatching { words.update(epub.id, epub.sha256, saved.id, "word", turkish) }.exceptionOrNull())
            }
            assertNotNull(runCatching { words.update(pdf.id, pdf.sha256, saved.id, "word", "anlam") }.exceptionOrNull())
            assertNotNull(runCatching { words.update(epub.id, "wrong", saved.id, "word", "anlam") }.exceptionOrNull())
            assertNotNull(runCatching { words.update("missing", epub.sha256, saved.id, "word", "anlam") }.exceptionOrNull())
            assertNotNull(runCatching { words.update(epub.id, epub.sha256, "missing", "word", "anlam") }.exceptionOrNull())
            assertEquals(saved, words.observe(epub.id, epub.sha256).first().single())

            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("UPDATE books SET sha256 = 'changed' WHERE id = 'epub'")
            assertNotNull(runCatching { words.update(epub.id, epub.sha256, saved.id, "word", "anlam") }.exceptionOrNull())
            sqlite.execSQL("UPDATE books SET sha256 = 'epub-hash' WHERE id = 'epub'")
            sqlite.execSQL("""
                CREATE TRIGGER reject_saved_word_edit BEFORE UPDATE ON saved_words
                BEGIN SELECT RAISE(ABORT, 'simulated update failure'); END
            """.trimIndent())
            assertNotNull(runCatching { words.update(epub.id, epub.sha256, saved.id, "word", "anlam") }.exceptionOrNull())
            assertEquals(saved, words.observe(epub.id, epub.sha256).first().single())
            sqlite.execSQL("DROP TRIGGER reject_saved_word_edit")
            words.update(epub.id, epub.sha256, saved.id, "word", "anlam")
            assertEquals(saved.copy(english = "word", turkish = "anlam", revision = saved.revision + 1),
                words.observe(epub.id, epub.sha256).first().single())
        }
    }
}
