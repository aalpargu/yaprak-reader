package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomEpubPassageRepository
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.reader.SourceTextAnchor
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EpubPassagePersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val epub = Book("epub", "EPUB", null, BookFormat.EPUB,
        "book.epub", "book.epub", 100, "epub-hash", 1)
    private val pdf = Book("pdf", "PDF", null, BookFormat.PDF,
        "book.pdf", "book.pdf", 100, "pdf-hash", 2, 10)
    private val anchor = SourceTextAnchor("OPS/chapter%201.xhtml", 2, 7, "a".repeat(64))

    @Test fun `v11 migration preserves all prior tables including PDF page notes`() = runBlocking {
        val name = "passage-migration-${UUID.randomUUID().toString().take(8)}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!
            .getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/11.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) {
                        sqlite.execSQL(indices.getJSONObject(j).getString("createSql")
                            .replace('$' + "{TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO books VALUES ('epub','Existing',NULL,'EPUB','book.epub','book.epub',100,'epub-hash',1,NULL)")
                sqlite.execSQL("INSERT INTO books VALUES ('pdf','PDF',NULL,'PDF','book.pdf','book.pdf',100,'pdf-hash',2,10)")
                sqlite.execSQL("INSERT INTO reading_progress VALUES ('epub','epub-hash',1,'saved','one.xhtml',5)")
                sqlite.execSQL("INSERT INTO observed_text VALUES ('epub','epub-hash',1,'one.xhtml','0/0',0,5,?)",
                    arrayOf("a".repeat(64)))
                sqlite.execSQL("INSERT INTO bookmarks VALUES ('bookmark','epub','epub-hash','location-key','First','saved','one.xhtml',1,6)")
                sqlite.execSQL("INSERT INTO book_epub_theme_overrides VALUES ('epub','epub-hash','SEPIA')")
                sqlite.execSQL("INSERT INTO book_epub_font_size_overrides VALUES ('epub','epub-hash',125)")
                sqlite.execSQL("INSERT INTO book_epub_font_overrides VALUES ('epub','epub-hash','SERIF')")
                sqlite.execSQL("INSERT INTO book_epub_margin_overrides VALUES ('epub','epub-hash',150)")
                sqlite.execSQL("INSERT INTO book_epub_line_spacing_overrides VALUES ('epub','epub-hash',140)")
                sqlite.execSQL("INSERT INTO saved_words VALUES ('word','epub','epub-hash','leaf','yaprak',7)")
                sqlite.execSQL("INSERT INTO book_notes VALUES ('general','epub','epub-hash','Eski not',9,NULL)")
                sqlite.execSQL("INSERT INTO book_notes VALUES ('pdf-page','pdf','pdf-hash','Sayfa notu',10,0)")
                sqlite.version = 11
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { db ->
                    assertPriorData(db)
                    val passages = RoomEpubPassageRepository(db)
                    assertTrue(passages.observe(epub.id, epub.sha256).first().isEmpty())
                    passages.add(epub.id, epub.sha256, "  ABC", anchor)
                    assertEquals("  ABC", passages.observe(epub.id, epub.sha256).first().single().text)
                }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                assertPriorData(db)
                assertEquals(anchor, RoomEpubPassageRepository(db)
                    .observe(epub.id, epub.sha256).first().single().anchor)
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    private fun assertPriorData(db: LibraryDatabase) {
        val sqlite = db.openHelper.writableDatabase
        listOf("reading_progress", "observed_text", "bookmarks", "book_epub_theme_overrides",
            "book_epub_font_size_overrides", "book_epub_font_overrides",
            "book_epub_margin_overrides", "book_epub_line_spacing_overrides", "saved_words")
            .forEach { table ->
                assertEquals(table, 1L, sqlite.compileStatement("SELECT count(*) FROM $table")
                    .simpleQueryForLong())
            }
        assertEquals(2L, sqlite.compileStatement("SELECT count(*) FROM books").simpleQueryForLong())
        assertEquals(2L, sqlite.compileStatement("SELECT count(*) FROM book_notes").simpleQueryForLong())
        assertEquals(0L, sqlite.compileStatement("SELECT pdfPageIndex FROM book_notes WHERE id='pdf-page'")
            .simpleQueryForLong())
        assertEquals("Eski not", sqlite.compileStatement("SELECT text FROM book_notes WHERE id='general'")
            .simpleQueryForString())
        assertEquals(1L, sqlite.compileStatement("SELECT count(*) FROM book_notes WHERE id='general' AND pdfPageIndex IS NULL")
            .simpleQueryForLong())
        assertEquals("saved", sqlite.compileStatement("SELECT locatorJson FROM reading_progress WHERE bookId='epub'")
            .simpleQueryForString())
        assertEquals("yaprak", sqlite.compileStatement("SELECT turkish FROM saved_words WHERE id='word'")
            .simpleQueryForString())
    }

    @Test fun `exact selected text persists with one source anchor and duplicate is explicit`() = runBlocking {
        val name = "passages-${UUID.randomUUID().toString().take(8)}.db"
        try {
            fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            open().useDatabase { db ->
                val library = RoomLibraryRepository(db.books())
                library.add(epub)
                library.add(pdf)
                val passages = RoomEpubPassageRepository(db)
                passages.add(epub.id, epub.sha256, "  ABC", anchor)
                val stored = passages.observe(epub.id, epub.sha256).first().single()
                assertEquals("  ABC", stored.text)
                assertEquals(anchor, stored.anchor)
                assertTrue(passages.observe(pdf.id, pdf.sha256).first().isEmpty())
                assertTrue(passages.observe(epub.id, "wrong-hash").first().isEmpty())
                val duplicate = runCatching { passages.add(epub.id, epub.sha256, "  ABC", anchor) }.exceptionOrNull()
                assertEquals("EPUB passage already saved", duplicate?.message)
                assertEquals(1L, db.openHelper.writableDatabase
                    .compileStatement("SELECT count(*) FROM epub_passages").simpleQueryForLong())
            }
            open().useDatabase { db ->
                val passages = RoomEpubPassageRepository(db)
                val saved = passages.observe(epub.id, epub.sha256).first().single()
                assertEquals("  ABC", saved.text)
                assertEquals(anchor, saved.anchor)
                assertNotNull(runCatching { passages.remove(pdf.id, pdf.sha256, saved.id) }.exceptionOrNull())
                assertNotNull(runCatching { passages.remove(epub.id, "wrong-hash", saved.id) }.exceptionOrNull())
                passages.remove(epub.id, epub.sha256, saved.id)
                assertTrue(passages.observe(epub.id, epub.sha256).first().isEmpty())
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun `invalid source ranges resources hashes and stale books cannot write`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val library = RoomLibraryRepository(db.books())
            library.add(epub)
            library.add(pdf)
            val passages = RoomEpubPassageRepository(db)
            suspend fun rejected(bookId: String, hash: String, text: String, source: SourceTextAnchor) {
                assertNotNull(runCatching { passages.add(bookId, hash, text, source) }.exceptionOrNull())
            }
            rejected(epub.id, epub.sha256, "   ", anchor.copy(start = 2, end = 5))
            rejected(epub.id, epub.sha256, "A".repeat(4097), anchor.copy(start = 0, end = 4097))
            rejected(epub.id, epub.sha256, "  ABC", anchor.copy(start = -1))
            rejected(epub.id, epub.sha256, "  ABC", anchor.copy(end = 4097))
            rejected(epub.id, epub.sha256, "  ABC", anchor.copy(end = 6))
            rejected(epub.id, epub.sha256, "  ABC", anchor.copy(nodeTextSha256 = "BAD"))
            listOf("/absolute.xhtml", "../chapter.xhtml", "%2e%2e/chapter.xhtml",
                "OPS/%2e%2e/chapter.xhtml", "https://example.com/chapter.xhtml",
                "OPS/chapter.xhtml?x=1", "OPS/chapter.xhtml#fragment", "OPS\\chapter.xhtml",
                "OPS%2Fchapter.xhtml", "OPS%5cchapter.xhtml")
                .forEach { href -> rejected(epub.id, epub.sha256, "  ABC", anchor.copy(resourceHref = href)) }
            rejected(pdf.id, pdf.sha256, "  ABC", anchor)
            rejected(epub.id, "wrong-hash", "  ABC", anchor)
            rejected("missing", epub.sha256, "  ABC", anchor)
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("UPDATE books SET sha256='changed' WHERE id='epub'")
            rejected(epub.id, epub.sha256, "  ABC", anchor)
            assertEquals(0L, sqlite.compileStatement("SELECT count(*) FROM epub_passages").simpleQueryForLong())
        }
    }

    @Test fun `changed book hash hides source passage and deletion cascades`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(epub)
            val passages = RoomEpubPassageRepository(db)
            passages.add(epub.id, epub.sha256, "  ABC", anchor)
            val id = passages.observe(epub.id, epub.sha256).first().single().id
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("UPDATE books SET sha256='changed' WHERE id='epub'")
            assertTrue(passages.observe(epub.id, epub.sha256).first().isEmpty())
            assertTrue(passages.observe(epub.id, "changed").first().isEmpty())
            assertNotNull(runCatching { passages.remove(epub.id, epub.sha256, id) }.exceptionOrNull())
            sqlite.execSQL("DELETE FROM books WHERE id='epub'")
            assertEquals(0L, sqlite.compileStatement("SELECT count(*) FROM epub_passages").simpleQueryForLong())
        }
    }
}
