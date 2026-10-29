package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomBookNoteRepository
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BookNotePersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val epub = Book("epub", "Existing", null, BookFormat.EPUB,
        "book.epub", "book.epub", 100, "epub-hash", 1)
    private val pdf = Book("pdf", "PDF", null, BookFormat.PDF,
        "book.pdf", "book.pdf", 100, "pdf-hash", 2, 10)

    @Test fun `v10 migration preserves existing book notes and every prior data table`() = runBlocking {
        val name = "page-notes-migration-${UUID.randomUUID().toString().take(8)}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!
            .getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/10.json")!!
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
                db.execSQL("INSERT INTO saved_words VALUES ('word','epub','epub-hash','leaf','yaprak',7)")
                db.execSQL("INSERT INTO book_notes VALUES ('old-note','epub','epub-hash','Eski not',9)")
                db.version = 10
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_10_11, LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { db ->
                    assertOldRows(db)
                    val saved = RoomBookNoteRepository(db).observe(epub.id, epub.sha256).first().single()
                    assertEquals("old-note", saved.id)
                    assertEquals("Eski not", saved.text)
                    assertEquals(9L, saved.createdAt)
                    assertEquals(null, saved.pdfPageIndex)
                    assertEquals(1L, db.openHelper.writableDatabase
                        .compileStatement("SELECT count(*) FROM book_notes WHERE pdfPageIndex IS NULL")
                        .simpleQueryForLong())
                }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                assertOldRows(db)
                val saved = RoomBookNoteRepository(db).observe(epub.id, epub.sha256).first().single()
                assertEquals("old-note", saved.id)
                assertEquals("Eski not", saved.text)
                assertEquals(9L, saved.createdAt)
                assertEquals(null, saved.pdfPageIndex)
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun `v9 migration preserves every existing table and saved words`() = runBlocking {
        val name = "notes-migration-${UUID.randomUUID().toString().take(8)}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!
            .getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/9.json")!!
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
                db.execSQL("INSERT INTO saved_words VALUES ('word','epub','epub-hash','leaf','yaprak',7)")
                db.version = 9
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_9_10, LibraryDatabase.MIGRATION_10_11, LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { db ->
                    assertOldRows(db)
                    assertTrue(RoomBookNoteRepository(db).observe(epub.id, epub.sha256).first().isEmpty())
                    RoomBookNoteRepository(db).add(epub.id, epub.sha256, "  İlk not  ")
                }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                assertOldRows(db)
                assertEquals("İlk not", RoomBookNoteRepository(db)
                    .observe(epub.id, epub.sha256).first().single().text)
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
            "book_epub_line_spacing_overrides", "saved_words").forEach { table ->
            assertEquals(table, 1L, sqlite.compileStatement("SELECT count(*) FROM $table").simpleQueryForLong())
        }
        assertEquals("Existing", sqlite.compileStatement("SELECT title FROM books WHERE id='epub'").simpleQueryForString())
        assertEquals("saved", sqlite.compileStatement("SELECT locatorJson FROM reading_progress WHERE bookId='epub'").simpleQueryForString())
        assertEquals("First", sqlite.compileStatement("SELECT label FROM bookmarks WHERE id='bookmark'").simpleQueryForString())
        assertEquals("yaprak", sqlite.compileStatement("SELECT turkish FROM saved_words WHERE id='word'").simpleQueryForString())
        assertEquals(140L, sqlite.compileStatement("SELECT lineHeightPercent FROM book_epub_line_spacing_overrides WHERE bookId='epub'").simpleQueryForLong())
        assertEquals(1L, sqlite.compileStatement("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='index_saved_words_bookId_contentSha256'").simpleQueryForLong())
        assertEquals(1L, sqlite.compileStatement("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='index_book_notes_bookId_contentSha256'").simpleQueryForLong())
    }

    @Test fun `notes persist and remain isolated to the book and content hash`() = runBlocking {
        val name = "notes-${UUID.randomUUID().toString().take(8)}.db"
        try {
            fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            open().useDatabase { db ->
                val library = RoomLibraryRepository(db.books())
                library.add(epub)
                library.add(pdf)
                val notes = RoomBookNoteRepository(db)
                notes.add(epub.id, epub.sha256, "  First line\nsecond line  ")
                notes.add(pdf.id, pdf.sha256, "PDF note")
                assertEquals("First line\nsecond line", notes.observe(epub.id, epub.sha256).first().single().text)
                assertEquals("PDF note", notes.observe(pdf.id, pdf.sha256).first().single().text)
            }
            open().useDatabase { db ->
                val notes = RoomBookNoteRepository(db)
                val epubNote = notes.observe(epub.id, epub.sha256).first().single()
                assertTrue(notes.observe(epub.id, "wrong-hash").first().isEmpty())
                assertTrue(notes.observe(pdf.id, epub.sha256).first().isEmpty())
                assertNotNull(runCatching { notes.remove(pdf.id, pdf.sha256, epubNote.id) }.exceptionOrNull())
                assertNotNull(runCatching { notes.remove(epub.id, "wrong-hash", epubNote.id) }.exceptionOrNull())
                assertEquals(epubNote, notes.observe(epub.id, epub.sha256).first().single())
                notes.remove(epub.id, epub.sha256, epubNote.id)
                assertTrue(notes.observe(epub.id, epub.sha256).first().isEmpty())
                assertEquals("PDF note", notes.observe(pdf.id, pdf.sha256).first().single().text)
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun `PDF page notes persist with zero based page while general notes remain unanchored`() = runBlocking {
        val name = "pdf-page-notes-${UUID.randomUUID().toString().take(8)}.db"
        try {
            fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            open().useDatabase { db ->
                RoomLibraryRepository(db.books()).add(pdf)
                val notes = RoomBookNoteRepository(db)
                notes.add(pdf.id, pdf.sha256, "Whole-book")
                notes.addAtPdfPage(pdf.id, pdf.sha256, "  First page  ", 0)
                notes.addAtPdfPage(pdf.id, pdf.sha256, "Last page", 9)
                val saved = notes.observe(pdf.id, pdf.sha256).first()
                assertEquals(3, saved.size)
                assertEquals(null, saved.single { it.text == "Whole-book" }.pdfPageIndex)
                assertEquals(0, saved.single { it.text == "First page" }.pdfPageIndex)
                assertEquals(9, saved.single { it.text == "Last page" }.pdfPageIndex)
            }
            open().useDatabase { db ->
                val notes = RoomBookNoteRepository(db)
                val saved = notes.observe(pdf.id, pdf.sha256).first()
                assertEquals(setOf(null, 0, 9), saved.map { it.pdfPageIndex }.toSet())
                val pageNote = saved.single { it.pdfPageIndex == 9 }
                notes.update(pdf.id, pdf.sha256, pageNote.id, "Updated")
                assertEquals(pageNote.copy(text = "Updated"),
                    notes.observe(pdf.id, pdf.sha256).first().single { it.id == pageNote.id })
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun `PDF page note rejects invalid page book format and stale hash without writing`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val library = RoomLibraryRepository(db.books())
            library.add(epub)
            library.add(pdf)
            val notes = RoomBookNoteRepository(db)
            suspend fun rejected(bookId: String, hash: String, text: String, page: Int) {
                assertNotNull(runCatching { notes.addAtPdfPage(bookId, hash, text, page) }.exceptionOrNull())
            }
            rejected(pdf.id, pdf.sha256, "Negative", -1)
            rejected(pdf.id, pdf.sha256, "Out of range", 10)
            rejected(epub.id, epub.sha256, "EPUB", 0)
            rejected(pdf.id, epub.sha256, "Wrong hash", 0)
            rejected("missing", pdf.sha256, "Missing", 0)
            rejected(pdf.id, pdf.sha256, "   ", 0)
            assertTrue(notes.observe(pdf.id, pdf.sha256).first().isEmpty())
            assertTrue(notes.observe(epub.id, epub.sha256).first().isEmpty())

            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("UPDATE books SET sha256 = 'changed' WHERE id = 'pdf'")
            rejected(pdf.id, pdf.sha256, "Stale", 0)
            assertEquals(0L, sqlite.compileStatement("SELECT count(*) FROM book_notes").simpleQueryForLong())
        }
    }

    @Test fun `editing persists in the same row with original identity and creation time`() = runBlocking {
        val name = "notes-update-${UUID.randomUUID().toString().take(8)}.db"
        try {
            fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            lateinit var original: com.yaprak.reader.domain.notes.BookNote
            open().useDatabase { db ->
                RoomLibraryRepository(db.books()).add(epub)
                val notes = RoomBookNoteRepository(db)
                notes.add(epub.id, epub.sha256, "Original")
                original = notes.observe(epub.id, epub.sha256).first().single()
                notes.update(epub.id, epub.sha256, original.id, "  Düzenlenmiş\nnot  ")
                assertEquals(original.copy(text = "Düzenlenmiş\nnot"),
                    notes.observe(epub.id, epub.sha256).first().single())
            }
            open().useDatabase { db ->
                val saved = RoomBookNoteRepository(db).observe(epub.id, epub.sha256).first().single()
                assertEquals(original.id, saved.id)
                assertEquals(original.createdAt, saved.createdAt)
                assertEquals("Düzenlenmiş\nnot", saved.text)
                assertEquals(1L, db.openHelper.writableDatabase
                    .compileStatement("SELECT count(*) FROM book_notes").simpleQueryForLong())
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun `editing rejects invalid text and foreign or stale note identities without changing rows`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val library = RoomLibraryRepository(db.books())
            library.add(epub)
            library.add(pdf)
            val notes = RoomBookNoteRepository(db)
            notes.add(epub.id, epub.sha256, "EPUB original")
            notes.add(pdf.id, pdf.sha256, "PDF original")
            val epubNote = notes.observe(epub.id, epub.sha256).first().single()
            val pdfNote = notes.observe(pdf.id, pdf.sha256).first().single()

            listOf("", "   \n  ", "a".repeat(2001), "😀".repeat(1001)).forEach { text ->
                assertNotNull(runCatching { notes.update(epub.id, epub.sha256, epubNote.id, text) }
                    .exceptionOrNull())
            }
            assertNotNull(runCatching { notes.update(epub.id, epub.sha256, "missing", "Wrong") }.exceptionOrNull())
            assertNotNull(runCatching { notes.update(pdf.id, pdf.sha256, epubNote.id, "Wrong") }.exceptionOrNull())
            assertNotNull(runCatching { notes.update(epub.id, pdf.sha256, epubNote.id, "Wrong") }.exceptionOrNull())
            assertNotNull(runCatching { notes.update("missing", epub.sha256, epubNote.id, "Wrong") }.exceptionOrNull())
            assertEquals(epubNote, notes.observe(epub.id, epub.sha256).first().single())
            assertEquals(pdfNote, notes.observe(pdf.id, pdf.sha256).first().single())

            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("UPDATE books SET sha256 = 'changed' WHERE id = 'epub'")
            assertNotNull(runCatching { notes.update(epub.id, epub.sha256, epubNote.id, "Wrong") }.exceptionOrNull())
            assertEquals("EPUB original", sqlite
                .compileStatement("SELECT text FROM book_notes WHERE id='${epubNote.id}'")
                .simpleQueryForString())
            sqlite.execSQL("UPDATE books SET sha256 = 'epub-hash' WHERE id = 'epub'")
            assertEquals(epubNote, notes.observe(epub.id, epub.sha256).first().single())
            assertEquals(pdfNote, notes.observe(pdf.id, pdf.sha256).first().single())
        }
    }

    @Test fun `failed database update retains old note text`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(epub)
            val notes = RoomBookNoteRepository(db)
            notes.add(epub.id, epub.sha256, "Before failure")
            val original = notes.observe(epub.id, epub.sha256).first().single()
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("CREATE TRIGGER fail_note_update BEFORE UPDATE OF text ON book_notes " +
                "BEGIN SELECT RAISE(ABORT, 'write failed'); END")
            try {
                assertNotNull(runCatching {
                    notes.update(epub.id, epub.sha256, original.id, "After failure")
                }.exceptionOrNull())
                assertEquals(original, notes.observe(epub.id, epub.sha256).first().single())
            } finally {
                sqlite.execSQL("DROP TRIGGER fail_note_update")
            }
        }
    }

    @Test fun `trimmed text must contain at most 2000 UTF-16 units`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(epub)
            val notes = RoomBookNoteRepository(db)
            listOf("", "   \n  ", "a".repeat(2001), "😀".repeat(1001)).forEach { text ->
                assertNotNull(runCatching { notes.add(epub.id, epub.sha256, text) }.exceptionOrNull())
            }
            assertTrue(notes.observe(epub.id, epub.sha256).first().isEmpty())
            notes.add(epub.id, epub.sha256, "  " + "😀".repeat(1000) + "  ")
            assertEquals(2000, notes.observe(epub.id, epub.sha256).first().single().text.length)
            assertNotNull(runCatching { notes.add(epub.id, "wrong", "Another note") }.exceptionOrNull())
            assertNotNull(runCatching { notes.add("missing", epub.sha256, "Another note") }.exceptionOrNull())
            assertEquals(1, notes.observe(epub.id, epub.sha256).first().size)
        }
    }

    @Test fun `changed book content hides old notes and book deletion cascades`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(epub)
            val notes = RoomBookNoteRepository(db)
            notes.add(epub.id, epub.sha256, "Old content note")
            val id = notes.observe(epub.id, epub.sha256).first().single().id
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("UPDATE books SET sha256 = 'changed' WHERE id = 'epub'")
            assertTrue(notes.observe(epub.id, epub.sha256).first().isEmpty())
            assertTrue(notes.observe(epub.id, "changed").first().isEmpty())
            assertNotNull(runCatching { notes.add(epub.id, epub.sha256, "Wrong") }.exceptionOrNull())
            assertNotNull(runCatching { notes.remove(epub.id, epub.sha256, id) }.exceptionOrNull())
            assertEquals(1L, sqlite.compileStatement("SELECT count(*) FROM book_notes").simpleQueryForLong())
            sqlite.execSQL("DELETE FROM books WHERE id = 'epub'")
            assertEquals(0L, sqlite.compileStatement("SELECT count(*) FROM book_notes").simpleQueryForLong())
        }
    }
}
