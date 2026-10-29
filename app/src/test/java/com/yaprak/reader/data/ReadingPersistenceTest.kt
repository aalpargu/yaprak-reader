package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
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
class ReadingPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("book", "Existing book", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "original-hash", 1)

    @Test fun `PDF restores last visited page without creating text observations`() = runBlocking {
        val name = "pdf-reading.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        val pdf = book.copy(format = BookFormat.PDF, originalName = "book.pdf", storageName = "book.pdf", pageCount = 20)
        open().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(pdf)
            val reading = RoomReadingStateRepository(db)
            listOf(0, 19, 3).forEach { page ->
                reading.record(pdf.id, pdf.sha256, ReadingSnapshot(com.yaprak.reader.formats.pdf.PdfPosition.encode(page)))
            }
        }
        open().useDatabase { db ->
            val reading = RoomReadingStateRepository(db)
            assertEquals(3, com.yaprak.reader.formats.pdf.PdfPosition.decode(reading.resume(pdf.id, pdf.sha256), 20))
            assertTrue(db.reading().observed(pdf.id, pdf.sha256).isEmpty())
            assertNull(reading.resume(pdf.id, "changed"))
        }
        context.deleteDatabase(name)
        Unit
    }

    @Test fun `version one migrates to five without losing books or duplicate index`() = runBlocking {
        val name = "migration.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        // Build the old database from the shipped v1 schema, not from v2 table definitions.
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/1.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entity = schema.getJSONArray("entities").getJSONObject(0)
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", "books"))
            val indexes = entity.getJSONArray("indices")
            for (i in 0 until indexes.length()) db.execSQL(indexes.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", "books"))
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO books VALUES ('book','Existing book',NULL,'EPUB','book.epub','book.epub',100,'original-hash',1,NULL)")
            db.version = 1
        }
        Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6, LibraryDatabase.MIGRATION_6_7, LibraryDatabase.MIGRATION_7_8, LibraryDatabase.MIGRATION_8_9, LibraryDatabase.MIGRATION_9_10, LibraryDatabase.MIGRATION_10_11, LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { db ->
                val library = RoomLibraryRepository(db.books())
                assertEquals(book, library.findById(book.id))
                assertNotNull(runCatching { library.add(book.copy(id = "duplicate")) }.exceptionOrNull())
                val reading = RoomReadingStateRepository(db)
                val location = ReadingLocation("{\"href\":\"one.xhtml\"}", "one.xhtml")
                reading.record(book.id, book.sha256, ReadingSnapshot(location))
                assertEquals(location, reading.resume(book.id, book.sha256))
                val themes = RoomBookThemeRepository(db)
                assertNull(themes.observe(book.id, book.sha256).first())
                assertNull(RoomBookFontSizeRepository(db).observe(book.id, book.sha256).first())
                themes.save(book.id, book.sha256, ReadingTheme.DARK)
                assertEquals(ReadingTheme.DARK, themes.observe(book.id, book.sha256).first())
            }
        context.deleteDatabase(name)
        Unit
    }

    @Test fun `reopen restores latest position without filling skipped chapters`() = runBlocking {
        val name = "reading.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        val first = ReadingLocation("first", "one.xhtml")
        val later = ReadingLocation("later", "three.xhtml")
        fun span(href: String) = ObservedTextSpan(href, "1/0", 0, 12, "a".repeat(64))
        open().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(book)
            val repository = RoomReadingStateRepository(db)
            repository.record(book.id, book.sha256, ReadingSnapshot(first, listOf(span(first.resourceHref))))
            repository.record(book.id, book.sha256, ReadingSnapshot(later, listOf(span(later.resourceHref))))
            repository.record(book.id, book.sha256, ReadingSnapshot(first, listOf(span(first.resourceHref))))
        }
        open().useDatabase { db ->
            val repository = RoomReadingStateRepository(db)
            assertEquals(first, repository.resume(book.id, book.sha256))
            assertNull(repository.resume(book.id, "changed-hash"))
            val observations = db.reading().observed(book.id, book.sha256)
            assertEquals(2, observations.size)
            assertEquals(setOf("one.xhtml", "three.xhtml"), observations.map { it.resourceHref }.toSet())
            assertTrue(db.reading().observed(book.id, "changed-hash").isEmpty())
        }
        context.deleteDatabase(name)
        Unit
    }

    @Test fun `invalid coverage rolls back progress and changed content cannot save`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(book)
            val repository = RoomReadingStateRepository(db)
            val location = ReadingLocation("first", "one.xhtml")
            repository.record(book.id, book.sha256, ReadingSnapshot(location))
            val invalid = ReadingSnapshot(ReadingLocation("later", "three.xhtml"), listOf(ObservedTextSpan("future.xhtml", "1/0", 0, 20, "a".repeat(64))))
            assertNotNull(runCatching { repository.record(book.id, book.sha256, invalid) }.exceptionOrNull())
            assertEquals(location, repository.resume(book.id, book.sha256))
            assertNotNull(runCatching { repository.record(book.id, "new-hash", ReadingSnapshot(location)) }.exceptionOrNull())
            assertTrue(db.reading().observed(book.id, book.sha256).isEmpty())
        }
    }
}
