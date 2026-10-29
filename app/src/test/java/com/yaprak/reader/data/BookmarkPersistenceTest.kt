package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.domain.settings.ReadingTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BookmarkPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("book", "Existing", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "hash", 1)
    private val first = BookmarkTarget("Başlangıç", ReadingLocation("{\"href\":\"one.xhtml\"}", "one.xhtml"))

    @Test fun `version two migration to five preserves library resume and observations while adding bookmarks`() = runBlocking {
        val name = "bookmarks-migration.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/2.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO books VALUES ('book','Existing',NULL,'EPUB','book.epub','book.epub',100,'hash',1,NULL)")
            db.execSQL("INSERT INTO reading_progress VALUES ('book','hash',1,'saved','one.xhtml',5)")
            db.execSQL("INSERT INTO observed_text VALUES ('book','hash',1,'one.xhtml','0/0',0,5,?)", arrayOf("a".repeat(64)))
            db.version = 2
        }
        Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6, LibraryDatabase.MIGRATION_6_7, LibraryDatabase.MIGRATION_7_8, LibraryDatabase.MIGRATION_8_9, LibraryDatabase.MIGRATION_9_10, LibraryDatabase.MIGRATION_10_11, LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { db ->
                assertEquals(book, RoomLibraryRepository(db.books()).findById(book.id))
                assertEquals("saved", RoomReadingStateRepository(db).resume(book.id, book.sha256)?.locatorJson)
                assertEquals(1, db.reading().observed(book.id, book.sha256).size)
                val bookmarks = RoomBookmarkRepository(db)
                bookmarks.add(book.id, book.sha256, first)
                assertEquals(first, bookmarks.observe(book.id, book.sha256).first().single().target)
                val themes = RoomBookThemeRepository(db)
                assertNull(themes.observe(book.id, book.sha256).first())
                assertNull(RoomBookFontSizeRepository(db).observe(book.id, book.sha256).first())
                themes.save(book.id, book.sha256, ReadingTheme.SEPIA)
                assertEquals(ReadingTheme.SEPIA, themes.observe(book.id, book.sha256).first())
                assertNotNull(runCatching { RoomLibraryRepository(db.books()).add(book.copy(id = "duplicate")) }.exceptionOrNull())
            }
        context.deleteDatabase(name)
        Unit
    }

    @Test fun `bookmark survives reopen deduplicates concurrent requests and does not mark anything read`() = runBlocking {
        val name = "bookmarks.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        open().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(book)
            val bookmarks = RoomBookmarkRepository(db)
            coroutineScope { repeat(4) { launch { bookmarks.add(book.id, book.sha256, first) } } }
            assertEquals(1, bookmarks.observe(book.id, book.sha256).first().size)
            assertNull(RoomReadingStateRepository(db).resume(book.id, book.sha256))
            assertTrue(db.reading().observed(book.id, book.sha256).isEmpty())
        }
        open().useDatabase { db ->
            val repository = RoomBookmarkRepository(db)
            val saved = repository.observe(book.id, book.sha256).first().single()
            assertEquals(first, saved.target)
            assertTrue(repository.observe(book.id, "changed").first().isEmpty())
            assertNotNull(runCatching { repository.remove(book.id, "changed", saved.id) }.exceptionOrNull())
            assertEquals(1, repository.observe(book.id, book.sha256).first().size)
            repository.remove(book.id, book.sha256, saved.id)
            assertTrue(repository.observe(book.id, book.sha256).first().isEmpty())
        }
        context.deleteDatabase(name)
        Unit
    }

    @Test fun `other books cannot delete a bookmark and changed or missing content cannot add`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val library = RoomLibraryRepository(db.books())
            library.add(book)
            library.add(book.copy(id = "other", sha256 = "other-hash"))
            val repository = RoomBookmarkRepository(db)
            repository.add(book.id, book.sha256, first)
            val saved = repository.observe(book.id, book.sha256).first().single()
            repository.remove("other", "other-hash", saved.id)
            assertEquals(1, repository.observe(book.id, book.sha256).first().size)
            listOf("missing" to "hash", "book" to "changed").forEach { (id, hash) ->
                assertNotNull(runCatching { repository.add(id, hash, first) }.exceptionOrNull())
            }
            db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = 'book'")
            assertTrue(repository.observe(book.id, book.sha256).first().isEmpty())
        }
    }

    @Test fun `PDF bookmarks persist separately and never create progress or observed text`() = runBlocking {
        val name = "pdf-bookmarks.db"
        context.deleteDatabase(name)
        val pdf = book.copy(id = "pdf", sha256 = "pdf-hash", format = BookFormat.PDF, pageCount = 20)
        val target = BookmarkTarget("Sayfa 20", com.yaprak.reader.formats.pdf.PdfPosition.encode(19))
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        open().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(book)
            RoomLibraryRepository(db.books()).add(pdf)
            val repository = RoomBookmarkRepository(db)
            repository.add(book.id, book.sha256, first)
            repository.add(pdf.id, pdf.sha256, target)
            repository.add(pdf.id, pdf.sha256, target)
            assertNull(RoomReadingStateRepository(db).resume(pdf.id, pdf.sha256))
            assertTrue(db.reading().observed(pdf.id, pdf.sha256).isEmpty())
        }
        open().useDatabase { db ->
            val repository = RoomBookmarkRepository(db)
            val saved = repository.observe(pdf.id, pdf.sha256).first().single()
            assertEquals(target, saved.target)
            repository.remove(book.id, book.sha256, saved.id)
            assertEquals(1, repository.observe(pdf.id, pdf.sha256).first().size)
            repository.remove(pdf.id, pdf.sha256, saved.id)
            assertTrue(repository.observe(pdf.id, pdf.sha256).first().isEmpty())
            assertEquals(first, repository.observe(book.id, book.sha256).first().single().target)
        }
        context.deleteDatabase(name)
        Unit
    }

    @Test fun `renaming persists trimmed label without changing bookmark anchor or reading evidence`() = runBlocking {
        val name = "bookmark-rename.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        val other = book.copy(id = "other", sha256 = "other-hash")
        val observation = ObservedTextSpan("one.xhtml", "0/0", 0, 5, "a".repeat(64))
        lateinit var original: com.yaprak.reader.domain.bookmarks.Bookmark
        lateinit var untouched: com.yaprak.reader.domain.bookmarks.Bookmark
        open().useDatabase { db ->
            val library = RoomLibraryRepository(db.books())
            library.add(book)
            library.add(other)
            val reading = RoomReadingStateRepository(db)
            reading.record(book.id, book.sha256, ReadingSnapshot(first.location, listOf(observation)))
            val bookmarks = RoomBookmarkRepository(db)
            bookmarks.add(book.id, book.sha256, first)
            bookmarks.add(other.id, other.sha256, first)
            original = bookmarks.observe(book.id, book.sha256).first().single()
            untouched = bookmarks.observe(other.id, other.sha256).first().single()
            bookmarks.rename(book.id, book.sha256, original.id, "  Yeni başlık  ")
            assertEquals(first.location, reading.resume(book.id, book.sha256))
            assertEquals(1, db.reading().observed(book.id, book.sha256).size)
        }
        open().useDatabase { db ->
            val bookmarks = RoomBookmarkRepository(db)
            val renamed = bookmarks.observe(book.id, book.sha256).first().single()
            assertEquals(original.id, renamed.id)
            assertEquals("Yeni başlık", renamed.target.label)
            assertEquals(original.target.location, renamed.target.location)
            assertEquals(original.createdAt, renamed.createdAt)
            assertEquals(untouched, bookmarks.observe(other.id, other.sha256).first().single())
            assertEquals(first.location, RoomReadingStateRepository(db).resume(book.id, book.sha256))
            assertEquals(listOf(observation), db.reading().observed(book.id, book.sha256).map {
                ObservedTextSpan(it.resourceHref, it.nodePath, it.startOffset, it.endOffset, it.textSha256, it.extractionVersion)
            })
        }
        context.deleteDatabase(name)
        Unit
    }

    @Test fun `rename rejects wrong book hash bookmark and invalid label without changing saved data`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val other = book.copy(id = "other", sha256 = "other-hash")
            val library = RoomLibraryRepository(db.books())
            library.add(book)
            library.add(other)
            val bookmarks = RoomBookmarkRepository(db)
            bookmarks.add(book.id, book.sha256, first)
            bookmarks.add(other.id, other.sha256, first)
            val saved = bookmarks.observe(book.id, book.sha256).first().single()
            val otherSaved = bookmarks.observe(other.id, other.sha256).first().single()
            assertNotNull(runCatching { bookmarks.rename(other.id, other.sha256, saved.id, "Wrong book") }.exceptionOrNull())
            assertNotNull(runCatching { bookmarks.rename(book.id, "changed", saved.id, "Wrong hash") }.exceptionOrNull())
            assertNotNull(runCatching { bookmarks.rename(book.id, book.sha256, "missing", "Wrong id") }.exceptionOrNull())
            listOf("", "  \t  ", "x".repeat(201)).forEach { invalid ->
                assertNotNull(runCatching { bookmarks.rename(book.id, book.sha256, saved.id, invalid) }.exceptionOrNull())
            }
            assertEquals(saved, bookmarks.observe(book.id, book.sha256).first().single())
            assertEquals(otherSaved, bookmarks.observe(other.id, other.sha256).first().single())
        }
    }
}
