package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.BookOrganizationEntity
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.ReadingProgressEntity
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookOrganization
import com.yaprak.reader.domain.library.BookReadingStatus
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BookOrganizationPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("book", "Kitap", "Yazar", BookFormat.EPUB,
        "source.epub", "book.epub", 100, "book-hash", 1)
    private val organization = BookOrganization(BookReadingStatus.READING, true,
        "  Dizi  ", "  Teknik  ", listOf(" Bilgisayar ", "BİLGİSAYAR", "", "Öğrenme"),
        listOf(" Ders ", "DERS", "Başvuru"))

    @Test fun `organization reopen preserves records`() = runBlocking {
        val name = "org-${UUID.randomUUID().toString().take(8)}.db"
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        val other = book.copy(id = "other", sha256 = "other-hash", storageName = "other.epub")
        val progress = ReadingProgressEntity(book.id, book.sha256, 1, "saved", "chapter", 987)
        try {
            open().useDatabase { db ->
                val repository = RoomLibraryRepository(db.books())
                // Import has no inferred reading state or inherited collection.
                repository.add(book.copy(organization = organization))
                repository.add(other)
                assertEquals(book, repository.findById(book.id))
                db.reading().save(progress)
                assertTrue(repository.updateOrganization(book.id, book.sha256, organization))
                assertTrue(repository.updateMetadata(book.id, book.sha256, "Yeni başlık", null))
            }
            open().useDatabase { db ->
                val repository = RoomLibraryRepository(db.books())
                val edited = book.copy(title = "Yeni başlık", authors = null, organization = organization.normalized())
                assertEquals(edited, repository.findById(book.id))
                assertEquals(edited, repository.findByHash(book.sha256))
                assertEquals(mapOf(edited.id to edited.copy(lastReadAt = progress.updatedAt), other.id to other),
                    repository.observeBooks().first().associateBy(Book::id))
                assertEquals(progress, db.reading().resume(book.id, book.sha256))
                assertFalse(repository.updateOrganization(book.id, "wrong-hash", BookOrganization()))
                assertFalse(repository.updateOrganization("missing", book.sha256, BookOrganization()))
                assertEquals(edited, repository.findById(book.id))
                assertTrue(repository.updateOrganization(book.id, book.sha256, BookOrganization()))
                assertEquals(edited.copy(organization = BookOrganization()), repository.findById(book.id))
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    @Test fun `active Room flow follows organization writes and stale content is never shown`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val repository = RoomLibraryRepository(db.books())
            repository.add(book)
            val updates = Channel<List<Book>>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { repository.observeBooks().collect { updates.send(it) } }
            suspend fun next() = withTimeout(10_000) { updates.receive().single() }
            try {
                assertEquals(BookOrganization(), next().organization)
                assertTrue(repository.updateOrganization(book.id, book.sha256, organization))
                assertEquals(organization.normalized(), next().organization)
                db.books().insertOrganization(BookOrganizationEntity(book.id, "stale-hash", "COMPLETED", true,
                    null, null, "[]", "[]"))
                assertEquals(BookOrganization(), next().organization)
                assertEquals(book, repository.findById(book.id))
                assertEquals(book, repository.findByHash(book.sha256))
                assertTrue(repository.updateOrganization(book.id, book.sha256, organization))
                assertEquals(organization.normalized(), next().organization)
                assertEquals(1, db.books().deleteExact(book.id, book.sha256))
                assertEquals(0L, db.openHelper.writableDatabase.compileStatement(
                    "SELECT count(*) FROM book_organizations").simpleQueryForLong())
            } finally { collector.cancelAndJoin(); updates.close() }
        }
        Unit
    }

    @Test fun `invalid organization never changes the stored row`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val repository = RoomLibraryRepository(db.books())
            repository.add(book)
            assertTrue(repository.updateOrganization(book.id, book.sha256, organization))
            listOf(
                organization.copy(series = "a".repeat(201)),
                organization.copy(genre = "a\nb"),
                organization.copy(tags = listOf("a".repeat(101))),
                organization.copy(collections = List(51) { "Raf $it" }),
            ).forEach { invalid ->
                assertTrue(runCatching { repository.updateOrganization(book.id, book.sha256, invalid) }
                    .exceptionOrNull() is IllegalArgumentException)
                assertEquals(organization.normalized(), repository.findById(book.id)?.organization)
            }
        }
        Unit
    }

    @Test fun `v12 migration preserves old tables`() = runBlocking {
        val name = "om-${UUID.randomUUID().toString().take(8)}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream(
            "com.yaprak.reader.data.persistence.LibraryDatabase/12.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val previous = linkedMapOf<String, List<List<String?>>>()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    for (j in 0 until (indices?.length() ?: 0)) sqlite.execSQL(indices!!.getJSONObject(j)
                        .getString("createSql").replace('$' + "{TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO books VALUES ('book','Kitap','Yazar','EPUB','source.epub','book.epub',100,'book-hash',1,NULL)")
                sqlite.execSQL("INSERT INTO reading_progress VALUES ('book','book-hash',1,'saved','chapter',5)")
                sqlite.execSQL("INSERT INTO observed_text VALUES ('book','book-hash',1,'chapter','0/0',0,5,?)", arrayOf("a".repeat(64)))
                sqlite.execSQL("INSERT INTO bookmarks VALUES ('mark','book','book-hash','key','Mark','saved','chapter',1,6)")
                sqlite.execSQL("INSERT INTO book_epub_theme_overrides VALUES ('book','book-hash','SEPIA')")
                sqlite.execSQL("INSERT INTO book_epub_font_size_overrides VALUES ('book','book-hash',125)")
                sqlite.execSQL("INSERT INTO book_epub_font_overrides VALUES ('book','book-hash','SERIF')")
                sqlite.execSQL("INSERT INTO book_epub_margin_overrides VALUES ('book','book-hash',150)")
                sqlite.execSQL("INSERT INTO book_epub_line_spacing_overrides VALUES ('book','book-hash',140)")
                sqlite.execSQL("INSERT INTO saved_words VALUES ('word','book','book-hash','leaf','yaprak',7)")
                sqlite.execSQL("INSERT INTO book_notes VALUES ('note','book','book-hash','Eski not',9,NULL)")
                sqlite.execSQL("INSERT INTO epub_passages VALUES ('passage','book','book-hash',1,'chapter',?,0,4,'leaf',10)", arrayOf("b".repeat(64)))
                for (i in 0 until entities.length()) {
                    val table = entities.getJSONObject(i).getString("tableName")
                    previous[table] = sqlite.rawQuery("SELECT * FROM $table", null).use { cursor ->
                        buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { column ->
                            if (cursor.isNull(column)) null else cursor.getString(column)
                        }) }
                    }
                }
                sqlite.version = 12
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { db ->
                    val sql = db.openHelper.writableDatabase
                    previous.forEach { (table, before) ->
                        val columns = if (table == "epub_passages")
                            "id,bookId,contentSha256,anchorVersion,resourceHref,nodeTextSha256,startOffset,endOffset,text,createdAt"
                            else if (table == "saved_words") "id,bookId,contentSha256,english,turkish,createdAt"
                            else "*"
                        val after = sql.query("SELECT $columns FROM $table").use { cursor ->
                            buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { column ->
                                if (cursor.isNull(column)) null else cursor.getString(column)
                            }) }
                        }
                        if (table == "saved_words") {
                            assertEquals(0L, sql.compileStatement("SELECT revision FROM saved_words").simpleQueryForLong())
                            assertEquals(0L, sql.compileStatement("SELECT COUNT(*) FROM word_review_events").simpleQueryForLong())
                            assertEquals(0L, sql.compileStatement("SELECT COUNT(*) FROM word_schedules").simpleQueryForLong())
                        }
                        assertEquals(table, before, after)
                    }
                    val repository = RoomLibraryRepository(db.books())
                    assertEquals(book, repository.findById(book.id))
                    assertTrue(repository.updateOrganization(book.id, book.sha256, organization))
                    assertNotNull(runCatching { repository.add(book.copy(id = "duplicate")) }.exceptionOrNull())
                }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                assertEquals(organization.normalized(), RoomLibraryRepository(db.books())
                    .findById(book.id)?.organization)
                assertEquals(1, db.books().deleteExact(book.id, book.sha256))
                assertEquals(0L, db.openHelper.writableDatabase.compileStatement(
                    "SELECT count(*) FROM book_organizations").simpleQueryForLong())
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }
}
