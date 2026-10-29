package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomBookFontRepository
import com.yaprak.reader.data.persistence.RoomBookFontSizeRepository
import com.yaprak.reader.data.persistence.RoomBookMarginsRepository
import com.yaprak.reader.data.persistence.RoomBookThemeRepository
import com.yaprak.reader.data.persistence.RoomBookmarkRepository
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.data.persistence.RoomReadingStateRepository
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.ReadingFont
import com.yaprak.reader.domain.settings.ReadingTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BookMarginsPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("book", "Existing", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "hash", 1)

    @Test fun `version six migration preserves books progress observations bookmarks theme font size and font family`() = runBlocking {
        val name = "book-margins-migration.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/6.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
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
            db.execSQL("INSERT INTO books VALUES ('book','Existing',NULL,'EPUB','book.epub','book.epub',100,'hash',1,NULL)")
            db.execSQL("INSERT INTO reading_progress VALUES ('book','hash',1,'saved','one.xhtml',5)")
            db.execSQL("INSERT INTO observed_text VALUES ('book','hash',1,'one.xhtml','0/0',0,5,?)", arrayOf("a".repeat(64)))
            db.execSQL("INSERT INTO bookmarks VALUES ('bookmark','book','hash','location-key','First','saved','one.xhtml',1,6)")
            db.execSQL("INSERT INTO book_epub_theme_overrides VALUES ('book','hash','SEPIA')")
            db.execSQL("INSERT INTO book_epub_font_size_overrides VALUES ('book','hash',125)")
            db.execSQL("INSERT INTO book_epub_font_overrides VALUES ('book','hash','SERIF')")
            db.version = 6
        }
        Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(LibraryDatabase.MIGRATION_6_7, LibraryDatabase.MIGRATION_7_8, LibraryDatabase.MIGRATION_8_9, LibraryDatabase.MIGRATION_9_10, LibraryDatabase.MIGRATION_10_11, LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { db ->
                assertEquals(book, RoomLibraryRepository(db.books()).findById(book.id))
                assertEquals("saved", RoomReadingStateRepository(db).resume(book.id, book.sha256)?.locatorJson)
                assertEquals(1, db.reading().observed(book.id, book.sha256).size)
                assertEquals("First", RoomBookmarkRepository(db).observe(book.id, book.sha256).first().single().target.label)
                assertEquals(ReadingTheme.SEPIA, RoomBookThemeRepository(db).observe(book.id, book.sha256).first())
                assertEquals(125, RoomBookFontSizeRepository(db).observe(book.id, book.sha256).first())
                assertEquals(ReadingFont.SERIF, RoomBookFontRepository(db).observe(book.id, book.sha256).first())
                assertNotNull(runCatching { RoomLibraryRepository(db.books()).add(book.copy(id = "duplicate")) }.exceptionOrNull())
                val margins = RoomBookMarginsRepository(db)
                assertNull(margins.observe(book.id, book.sha256).first())
                margins.save(book.id, book.sha256, 150)
                assertEquals(150, margins.observe(book.id, book.sha256).first())
            }
        Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
            assertEquals(book, RoomLibraryRepository(db.books()).findById(book.id))
            assertEquals("saved", RoomReadingStateRepository(db).resume(book.id, book.sha256)?.locatorJson)
            assertEquals(1, db.reading().observed(book.id, book.sha256).size)
            assertEquals("First", RoomBookmarkRepository(db).observe(book.id, book.sha256).first().single().target.label)
            assertEquals(ReadingTheme.SEPIA, RoomBookThemeRepository(db).observe(book.id, book.sha256).first())
            assertEquals(125, RoomBookFontSizeRepository(db).observe(book.id, book.sha256).first())
            assertEquals(ReadingFont.SERIF, RoomBookFontRepository(db).observe(book.id, book.sha256).first())
            assertEquals(150, RoomBookMarginsRepository(db).observe(book.id, book.sha256).first())
        }
        context.deleteDatabase(name)
        Unit
    }

    @Test fun `margins isolate books reject PDF and hash mismatch and cascade on delete`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val other = book.copy(id = "other", sha256 = "other-hash")
            val pdf = book.copy(id = "pdf", sha256 = "pdf-hash", format = BookFormat.PDF, pageCount = 2)
            val library = RoomLibraryRepository(db.books())
            listOf(book, other, pdf).forEach { library.add(it) }
            val margins = RoomBookMarginsRepository(db)
            margins.save(book.id, book.sha256, 150)
            margins.save(other.id, other.sha256, 80)
            assertEquals(150, margins.observe(book.id, book.sha256).first())
            assertEquals(80, margins.observe(other.id, other.sha256).first())
            assertNull(margins.observe(book.id, "changed-hash").first())
            assertNull(margins.observe(pdf.id, pdf.sha256).first())
            assertNotNull(runCatching { margins.save(book.id, "changed-hash", 120) }.exceptionOrNull())
            assertNotNull(runCatching { margins.save(pdf.id, pdf.sha256, 120) }.exceptionOrNull())
            assertNotNull(runCatching { margins.save("missing", "hash", 120) }.exceptionOrNull())
            assertEquals(150, margins.observe(book.id, book.sha256).first())
            margins.save(book.id, book.sha256, null)
            assertNull(margins.observe(book.id, book.sha256).first())
            margins.save(book.id, book.sha256, 100)
            assertEquals(100, margins.observe(book.id, book.sha256).first())
            db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = 'new-hash' WHERE id = 'book'")
            assertNull(margins.observe(book.id, book.sha256).first())
            assertNull(margins.observe(book.id, "new-hash").first())
            db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = 'book'")
            assertEquals(0L, db.openHelper.writableDatabase.compileStatement("SELECT count(*) FROM book_epub_margin_overrides WHERE bookId = 'book'").simpleQueryForLong())
            assertEquals(80, margins.observe(other.id, other.sha256).first())
        }
    }

    @Test fun `margins range and ten percent step reject invalid saves without replacing valid value`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(book)
            val margins = RoomBookMarginsRepository(db)
            margins.save(book.id, book.sha256, 50)
            assertEquals(50, margins.observe(book.id, book.sha256).first())
            margins.save(book.id, book.sha256, 200)
            assertEquals(200, margins.observe(book.id, book.sha256).first())
            listOf(40, 49, 51, 101, 201, 210).forEach { invalid ->
                assertNotNull(runCatching { margins.save(book.id, book.sha256, invalid) }.exceptionOrNull())
                assertEquals(200, margins.observe(book.id, book.sha256).first())
            }
        }
    }

    @Test fun `corrupt stored margins are reported instead of silently replaced`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(book)
            val margins = RoomBookMarginsRepository(db)
            margins.save(book.id, book.sha256, 120)
            listOf(49, 101, 201).forEach { invalid ->
                db.openHelper.writableDatabase.execSQL("UPDATE book_epub_margin_overrides SET marginsPercent = $invalid WHERE bookId = 'book'")
                assertNotNull(runCatching { margins.observe(book.id, book.sha256).first() }.exceptionOrNull())
            }
        }
    }
}
