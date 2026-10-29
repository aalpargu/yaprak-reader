package com.yaprak.reader.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.settings.*
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
@Config(sdk = [34], application = Application::class)
class BookSpacingPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val book = Book("book", "Existing", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "hash", 1)
    private val custom = BookSpacingSettings(BookSpacingChoice.Custom(200), BookSpacingChoice.Custom(0))

    @Test fun pairedChoicesSurviveReopenAndInheritRemovesOnlyOwnedRow() = runBlocking {
        val name = "bs-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                RoomLibraryRepository(db.books()).add(book)
                RoomBookThemeRepository(db).save(book.id, book.sha256, ReadingTheme.AMOLED)
                RoomBookSpacingRepository(db).save(book.id, book.sha256, custom)
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                val repository = RoomBookSpacingRepository(db)
                assertEquals(custom, repository.observe(book.id, book.sha256).first())
                val mixed = BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Inherit)
                repository.save(book.id, book.sha256, mixed)
                assertEquals(mixed, repository.observe(book.id, book.sha256).first())
                repository.save(book.id, book.sha256, BookSpacingSettings())
                assertEquals(BookSpacingSettings(), repository.observe(book.id, book.sha256).first())
                assertEquals(0L, db.openHelper.writableDatabase.compileStatement("SELECT COUNT(*) FROM book_epub_spacing_overrides").simpleQueryForLong())
                assertEquals(ReadingTheme.AMOLED, RoomBookThemeRepository(db).observe(book.id, book.sha256).first())
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    @Test fun isolationHashPdfMissingBookAndCascadeAreEnforced() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val other = book.copy(id = "other", sha256 = "other-hash")
            val pdf = book.copy(id = "pdf", sha256 = "pdf-hash", format = BookFormat.PDF, pageCount = 2)
            val library = RoomLibraryRepository(db.books())
            listOf(book, other, pdf).forEach { library.add(it) }
            val repository = RoomBookSpacingRepository(db)
            repository.save(book.id, book.sha256, custom)
            val publisher = BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Publisher)
            repository.save(other.id, other.sha256, publisher)
            for ((id, hash) in listOf(book.id to "changed", pdf.id to pdf.sha256, "missing" to "hash")) {
                assertEquals(BookSpacingSettings(), repository.observe(id, hash).first())
                assertNotNull(runCatching { repository.save(id, hash, publisher) }.exceptionOrNull())
            }
            assertEquals(custom, repository.observe(book.id, book.sha256).first())
            db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = 'changed' WHERE id = 'book'")
            assertEquals(BookSpacingSettings(), repository.observe(book.id, "changed").first())
            assertEquals(BookSpacingSettings(), repository.observe(book.id, book.sha256).first())
            db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = 'book'")
            assertEquals(0L, db.openHelper.writableDatabase.compileStatement("SELECT COUNT(*) FROM book_epub_spacing_overrides WHERE bookId = 'book'").simpleQueryForLong())
            assertEquals(publisher, repository.observe(other.id, other.sha256).first())
        }
    }

    @Test fun failedReplacementRollsBackWholePairAndCorruptionIsNotNormalized() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(book)
            val repository = RoomBookSpacingRepository(db)
            repository.save(book.id, book.sha256, custom)
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER refuse_spacing BEFORE INSERT ON book_epub_spacing_overrides BEGIN SELECT RAISE(ABORT, 'controlled'); END")
            assertNotNull(runCatching { repository.save(book.id, book.sha256, BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Custom(100))) }.exceptionOrNull())
            assertEquals(custom, repository.observe(book.id, book.sha256).first())
            sql.execSQL("DROP TRIGGER refuse_spacing")
            for (mutation in listOf("paragraphMode = 'UNKNOWN'", "paragraphSpacingPercent = 201",
                "paragraphSpacingPercent = 11", "wordSpacingPercent = 110",
                "paragraphSpacingPercent = 4294967296", "wordSpacingPercent = 4294967296",
                "paragraphSpacingPercent = 20.5", "wordSpacingPercent = 20.5",
                "paragraphSpacingPercent = 'corrupt'", "wordSpacingPercent = 'corrupt'",
                "wordMode = 'PUBLISHER'", "paragraphMode = 'CUSTOM', paragraphSpacingPercent = NULL",
                "paragraphMode = 'INHERIT', paragraphSpacingPercent = NULL, wordMode = 'INHERIT', wordSpacingPercent = NULL")) {
                repository.save(book.id, book.sha256, custom)
                sql.execSQL("UPDATE book_epub_spacing_overrides SET " + mutation)
                assertNotNull(mutation, runCatching { repository.observe(book.id, book.sha256).first() }.exceptionOrNull())
            }
        }
    }

    private fun old(name: String) {
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/20.json")!!
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
            sql.execSQL("INSERT INTO books VALUES ('book','Existing',NULL,'EPUB','book.epub','book.epub',100,'hash',1,NULL)")
            sql.execSQL("INSERT INTO reading_progress VALUES ('book','hash',1,'saved','one.xhtml',5)")
            sql.execSQL("INSERT INTO book_notes VALUES ('note','book','hash','Exact note',8,NULL)")
            sql.execSQL("INSERT INTO saved_words VALUES ('word','book','hash','leaf','yaprak',7,3)")
            sql.execSQL("INSERT INTO book_epub_theme_overrides VALUES ('book','hash','AMOLED')")
            sql.execSQL("INSERT INTO book_epub_line_spacing_overrides VALUES ('book','hash',NULL)")
            sql.version = 20
        }
    }

    @Test fun versionTwentyMigratesWithoutRewritingOriginalDataOrInventingOverrides() = runBlocking {
        val name = "bsm-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            old(name)
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { db ->
                    val sql = db.openHelper.writableDatabase
                    assertEquals(26, sql.version)
                    assertEquals(book, RoomLibraryRepository(db.books()).findById(book.id))
                    assertEquals("saved", RoomReadingStateRepository(db).resume(book.id, book.sha256)!!.locatorJson)
                    assertEquals("Exact note", sql.compileStatement("SELECT text FROM book_notes").simpleQueryForString())
                    assertEquals(3L, sql.compileStatement("SELECT revision FROM saved_words").simpleQueryForLong())
                    assertEquals(ReadingTheme.AMOLED, RoomBookThemeRepository(db).observe(book.id, book.sha256).first())
                    assertEquals(BookLineHeightChoice.Publisher, RoomBookLineHeightRepository(db).observe(book.id, book.sha256).first())
                    val spacing = RoomBookSpacingRepository(db)
                    assertEquals(BookSpacingSettings(), spacing.observe(book.id, book.sha256).first())
                    assertEquals(0L, sql.compileStatement("SELECT COUNT(*) FROM book_epub_spacing_overrides").simpleQueryForLong())
                    sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    spacing.save(book.id, book.sha256, custom)
                }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                assertEquals(custom, RoomBookSpacingRepository(db).observe(book.id, book.sha256).first())
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    @Test fun missingMigrationRefusesAndKeepsVersionTwentyRows() {
        val name = "bsr-" + UUID.randomUUID().toString().take(8) + ".db"
        try {
            old(name)
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            try { assertThrows(IllegalStateException::class.java) { db.openHelper.writableDatabase } }
            finally { db.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
                assertEquals(20, sql.version)
                sql.rawQuery("SELECT text FROM book_notes", null).use { assertTrue(it.moveToFirst()); assertEquals("Exact note", it.getString(0)) }
                sql.rawQuery("SELECT revision FROM saved_words", null).use { assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }
}
