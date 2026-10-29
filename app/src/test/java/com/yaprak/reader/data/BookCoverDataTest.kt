package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.covers.*
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.removing.DefaultBookRemover
import com.yaprak.reader.data.storage.BookFileCleanup
import com.yaprak.reader.data.storage.BookOperationCoordinator
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.fixtures.CoverTestImages
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookCoverDataTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("book", "Kitap", "Yazar", BookFormat.EPUB, "source.epub", "book.epub", 4, "book-hash", 1)
    private val png = CoverTestImages.png(80, 120)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun cover(bytes: ByteArray = png, id: String = UUID.randomUUID().toString()) = BookCover(id, "$id.png", "image/png", bytes.size.toLong(), hash(bytes), 80, 120)
    private fun db() = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()

    @Test fun `replace reopen reset`() = runBlocking {
        val name = "cv-${UUID.randomUUID().toString().take(8)}.db"
        val directory = temp.newFolder("covers")
        val files = LocalCoverFiles(directory)
        var saved: BookCover? = null
        val organization = BookOrganization(favorite = true, collections = listOf("Raf"))
        val progress = ReadingProgressEntity(book.id, book.sha256, 1, "saved", "chapter", 42)
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            open().useDatabase { database ->
                val repository = RoomLibraryRepository(database.books())
                repository.add(book.copy(customCover = cover()))
                assertNull(repository.findById(book.id)!!.customCover)
                repository.updateOrganization(book.id, book.sha256, organization)
                database.reading().save(progress)
                val editor = DefaultBookCoverEditor(database, files, CoverDocumentProvider { ByteArrayInputStream(png) }, BookOperationCoordinator())
                assertEquals(CoverEditResult.Changed(), editor.replace(book.id, book.sha256, null, "source"))
                saved = repository.findById(book.id)!!.customCover!!
                assertArrayEquals(png, files.resolve(saved!!.storageName).readBytes())
                assertEquals(book.copy(organization = organization, customCover = saved), repository.findByHash(book.sha256))
                assertEquals(saved, repository.observeBooks().first().single().customCover)
                assertEquals(progress, database.reading().resume(book.id, book.sha256))
            }
            open().useDatabase { database ->
                val repository = RoomLibraryRepository(database.books())
                assertEquals(saved, repository.findById(book.id)!!.customCover)
                val editor = DefaultBookCoverEditor(database, files, CoverDocumentProvider { error("Reset must not open source") }, BookOperationCoordinator())
                assertEquals(CoverEditResult.Stale, editor.reset(book.id, book.sha256, null))
                assertEquals(CoverEditResult.Reset(), editor.reset(book.id, book.sha256, saved!!.id))
                assertFalse(files.resolve(saved!!.storageName).exists())
                assertNull(repository.findById(book.id)!!.customCover)
                assertEquals(progress, database.reading().resume(book.id, book.sha256))
                assertEquals(organization, repository.findById(book.id)!!.organization)
                assertEquals(CoverEditResult.Reset(), editor.reset(book.id, book.sha256, null))
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    @Test fun `stale snapshots cannot overwrite`() = runBlocking {
        val directory = temp.newFolder("covers")
        db().useDatabase { database ->
            val repository = RoomLibraryRepository(database.books())
            repository.add(book)
            var opened = 0
            val editor = DefaultBookCoverEditor(database, LocalCoverFiles(directory), CoverDocumentProvider { opened++; ByteArrayInputStream(png) }, BookOperationCoordinator())
            assertEquals(CoverEditResult.Missing, editor.replace("missing", book.sha256, null, "source"))
            assertEquals(CoverEditResult.Stale, editor.replace(book.id, "wrong", null, "source"))
            assertEquals(0, opened)
            assertEquals(CoverEditResult.Changed(), editor.replace(book.id, book.sha256, null, "source"))
            val first = repository.findById(book.id)!!.customCover!!
            assertEquals(CoverEditResult.Stale, editor.replace(book.id, book.sha256, null, "source"))
            assertEquals(1, opened)
            assertEquals(CoverEditResult.Changed(), editor.replace(book.id, book.sha256, first.id, "source"))
            val second = repository.findById(book.id)!!.customCover!!
            assertNotEquals(first.id, second.id)
            assertFalse(File(directory, first.storageName).exists())
            assertEquals(CoverEditResult.Stale, editor.reset(book.id, book.sha256, first.id))
            assertEquals(CoverEditResult.Stale, editor.replace(book.id, book.sha256, first.id, "source"))
            assertEquals(2, opened)
            assertEquals(second, repository.findById(book.id)!!.customCover)
            database.bookCovers().upsert(second.toCoverEntity(book.id, "stale-hash"))
            assertNull(repository.findById(book.id)!!.customCover)
            assertNull(repository.findByHash(book.sha256)!!.customCover)
            assertNull(repository.observeBooks().first().single().customCover)
            assertEquals(listOf(second.storageName), database.bookCovers().storedFileNames())
        }
        Unit
    }

    @Test fun `failed edit retains old bytes`() = runBlocking {
        val directory = temp.newFolder("covers")
        val files = LocalCoverFiles(directory)
        db().useDatabase { database ->
            val repository = RoomLibraryRepository(database.books())
            repository.add(book)
            var bytes = png
            val provider = CoverDocumentProvider { ByteArrayInputStream(bytes) }
            val editor = DefaultBookCoverEditor(database, files, provider, BookOperationCoordinator())
            assertEquals(CoverEditResult.Changed(), editor.replace(book.id, book.sha256, null, "source"))
            val before = repository.findById(book.id)!!.customCover!!
            for (invalid in listOf(byteArrayOf(1, 2), png.copyOf(png.size / 2))) {
                bytes = invalid
                assertEquals(CoverEditResult.Failed(CoverEditFailure.INVALID_IMAGE), editor.replace(book.id, book.sha256, before.id, "source"))
                assertEquals(before, repository.findById(book.id)!!.customCover)
                assertEquals(listOf(before.storageName), directory.list()!!.toList())
            }
            bytes = png
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER cover_block BEFORE UPDATE ON book_covers BEGIN SELECT RAISE(ABORT, 'blocked'); END")
            assertEquals(CoverEditResult.Failed(CoverEditFailure.IO), editor.replace(book.id, book.sha256, before.id, "source"))
            assertEquals(before, repository.findById(book.id)!!.customCover)
            assertArrayEquals(png, files.resolve(before.storageName).readBytes())
            assertEquals(listOf(before.storageName), directory.list()!!.toList())
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER cover_block")
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER cover_reset_block BEFORE DELETE ON book_covers BEGIN SELECT RAISE(ABORT, 'blocked'); END")
            assertEquals(CoverEditResult.Failed(CoverEditFailure.IO), editor.reset(book.id, book.sha256, before.id))
            assertEquals(before, repository.findById(book.id)!!.customCover)
            assertTrue(files.resolve(before.storageName).exists())
        }
        Unit
    }

    @Test fun `commit rechecks provider time revision`() = runBlocking {
        val directory = temp.newFolder("covers")
        val files = LocalCoverFiles(directory)
        db().useDatabase { database ->
            database.books().insert(book.toEntity())
            val previous = cover(); val newer = cover()
            files.resolve(previous.storageName).writeBytes(png)
            files.resolve(newer.storageName).writeBytes(png)
            database.bookCovers().upsert(previous.toCoverEntity(book.id, book.sha256))
            val editor = DefaultBookCoverEditor(database, files, CoverDocumentProvider {
                // Models an external writer while the provider stream is being opened.
                database.openHelper.writableDatabase.execSQL("UPDATE book_covers SET coverId=?, storageName=? WHERE bookId=?",
                    arrayOf(newer.id, newer.storageName, book.id))
                ByteArrayInputStream(png)
            }, BookOperationCoordinator())
            assertEquals(CoverEditResult.Stale, editor.replace(book.id, book.sha256, previous.id, "source"))
            assertEquals(newer, RoomLibraryRepository(database.books()).findById(book.id)!!.customCover)
            assertEquals(setOf(previous.storageName, newer.storageName), directory.list()!!.toSet())
            files.recover(database.bookCovers().storedFileNames())
            assertEquals(listOf(newer.storageName), directory.list()!!.toList())
        }
        Unit
    }

    @Test fun `source bounds access and cancellation`() = runBlocking {
        val directory = temp.newFolder("covers")
        db().useDatabase { database ->
            database.books().insert(book.toEntity())
            val tooLarge = DefaultBookCoverEditor(database, LocalCoverFiles(directory, 20),
                CoverDocumentProvider { ByteArrayInputStream(png) }, BookOperationCoordinator())
            assertEquals(CoverEditResult.Failed(CoverEditFailure.TOO_LARGE), tooLarge.replace(book.id, book.sha256, null, "source"))
            val denied = DefaultBookCoverEditor(database, LocalCoverFiles(directory),
                CoverDocumentProvider { throw SecurityException("Denied") }, BookOperationCoordinator())
            assertEquals(CoverEditResult.Failed(CoverEditFailure.ACCESS), denied.replace(book.id, book.sha256, null, "source"))
            val cancelled = async {
                val coroutine = currentCoroutineContext()
                DefaultBookCoverEditor(database, LocalCoverFiles(directory), CoverDocumentProvider {
                    object : InputStream() {
                        override fun read(): Int { coroutine.cancel(); return 1 }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { coroutine.cancel(); return 0 }
                    }
                }, BookOperationCoordinator()).replace(book.id, book.sha256, null, "source")
            }
            assertTrue(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
            assertNull(database.bookCovers().find(book.id))
            assertTrue(directory.list()!!.isEmpty())
            // A valid source with one zero-length read must still be copied exactly.
            val zeroThenBytes = object : ByteArrayInputStream(png) {
                var zero = true
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = if (zero) { zero = false; 0 } else super.read(buffer, offset, length)
            }
            val editor = DefaultBookCoverEditor(database, LocalCoverFiles(directory), CoverDocumentProvider { zeroThenBytes }, BookOperationCoordinator())
            assertEquals(CoverEditResult.Changed(), editor.replace(book.id, book.sha256, null, "source"))
            assertArrayEquals(png, directory.listFiles()!!.single().readBytes())
        }
        Unit
    }

    @Test fun `private files verified recovery`() = runBlocking {
        val directory = temp.newFolder("covers")
        val outside = temp.newFile("keep.png").apply { writeBytes(png) }
        val files = LocalCoverFiles(directory)
        assertFalse(files.removeCommitted("../keep.png"))
        assertFalse(files.removeCommitted("..\\keep.png"))
        assertTrue(outside.exists())
        val retained = cover()
        files.resolve(retained.storageName).writeBytes(png)
        val orphan = cover()
        files.resolve(orphan.storageName).writeBytes(png)
        File(directory, "orphan.part").writeBytes(png)
        files.recover(listOf(retained.storageName))
        assertEquals(listOf(retained.storageName), directory.list()!!.toList())
        val verified = VerifiedCoverFile(directory)
        assertArrayEquals(png, verified.read(retained))
        val corrupt = png.clone().also { it[it.lastIndex] = 1 }
        files.resolve(retained.storageName).writeBytes(corrupt)
        assertNotNull(runCatching { verified.read(retained) }.exceptionOrNull())
        files.resolve(retained.storageName).writeBytes(png + 1)
        assertNotNull(runCatching { verified.read(retained) }.exceptionOrNull())
        assertTrue(files.removeCommitted(retained.storageName))
        assertTrue(files.removeCommitted(retained.storageName))
    }

    @Test fun `book removal cleans cover and retries orphan`() = runBlocking {
        val directory = temp.newFolder("covers")
        val files = LocalCoverFiles(directory)
        db().useDatabase { database ->
            database.books().insert(book.toEntity())
            val kept = book.copy(id = "keep", sha256 = "keep-hash", storageName = "keep.epub")
            database.books().insert(kept.toEntity())
            val first = cover(); val second = cover()
            files.resolve(first.storageName).writeBytes(png); files.resolve(second.storageName).writeBytes(png)
            database.bookCovers().upsert(first.toCoverEntity(book.id, book.sha256))
            database.bookCovers().upsert(second.toCoverEntity(kept.id, kept.sha256))
            val remover = DefaultBookRemover(database, BookFileCleanup { true }, BookOperationCoordinator(), covers = CoverFileCleanup { false })
            assertEquals(BookRemovalResult.Removed(true), remover.remove(book.id, book.sha256))
            assertNull(database.bookCovers().find(book.id))
            assertTrue(files.resolve(first.storageName).exists())
            files.recover(database.bookCovers().storedFileNames())
            assertFalse(files.resolve(first.storageName).exists())
            assertTrue(files.resolve(second.storageName).exists())
            assertEquals(second, RoomLibraryRepository(database.books()).findById(kept.id)!!.customCover)
            val real = DefaultBookRemover(database, BookFileCleanup { true }, BookOperationCoordinator(), covers = files)
            assertEquals(BookRemovalResult.Removed(), real.remove(kept.id, kept.sha256))
            assertFalse(files.resolve(second.storageName).exists())
        }
        Unit
    }

    @Test fun `damaged shared cover is retained`() = runBlocking {
        val directory = temp.newFolder("covers")
        val files = LocalCoverFiles(directory)
        db().useDatabase { database ->
            database.books().insert(book.toEntity())
            val other = book.copy(id = "other", sha256 = "other-hash")
            database.books().insert(other.toEntity())
            val shared = cover()
            files.resolve(shared.storageName).writeBytes(png)
            database.bookCovers().upsert(shared.toCoverEntity(book.id, book.sha256))
            database.openHelper.writableDatabase.execSQL("DROP INDEX index_book_covers_storageName")
            database.bookCovers().upsert(shared.copy(id = UUID.randomUUID().toString()).let {
                BookCoverEntity(other.id, other.sha256, it.id, shared.storageName, shared.mediaType, shared.byteSize, shared.sha256, shared.width, shared.height)
            })
            val remover = DefaultBookRemover(database, BookFileCleanup { true }, BookOperationCoordinator(), covers = files)
            assertEquals(BookRemovalResult.Removed(), remover.remove(book.id, book.sha256))
            assertTrue(files.resolve(shared.storageName).exists())
            assertEquals(BookRemovalResult.Removed(), remover.remove(other.id, other.sha256))
            assertFalse(files.resolve(shared.storageName).exists())
        }
        Unit
    }

    @Test fun `v13 migration preserves all tables`() = runBlocking {
        val name = "cm-${UUID.randomUUID().toString().take(8)}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yaprak.reader.data.persistence.LibraryDatabase/13.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val before = linkedMapOf<String, List<List<String?>>>()
        fun rows(cursor: android.database.Cursor) = cursor.use { buildList { while (it.moveToNext()) add(List(it.columnCount) { column -> if (it.isNull(column)) null else it.getString(column) }) } }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    for (j in 0 until (indices?.length() ?: 0)) sqlite.execSQL(indices!!.getJSONObject(j).getString("createSql").replace('$' + "{TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO books VALUES ('book','Kitap','Yazar','EPUB','source.epub','book.epub',4,'book-hash',1,NULL)")
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
                sqlite.execSQL("INSERT INTO book_organizations VALUES ('book','book-hash','READING',1,'Seri','Tür','[\"Etiket\"]','[\"Raf\"]')")
                for (i in 0 until entities.length()) { val table = entities.getJSONObject(i).getString("tableName"); before[table] = rows(sqlite.rawQuery("SELECT * FROM $table", null)) }
                sqlite.version = 13
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build().useDatabase { database ->
                before.forEach { (table, previous) ->
                    val columns = if (table == "epub_passages")
                        "id,bookId,contentSha256,anchorVersion,resourceHref,nodeTextSha256,startOffset,endOffset,text,createdAt"
                        else if (table == "saved_words") "id,bookId,contentSha256,english,turkish,createdAt"
                        else "*"
                    assertEquals(table, previous, rows(database.openHelper.writableDatabase.query("SELECT $columns FROM $table")))
                }
                assertEquals(0L, database.openHelper.writableDatabase.compileStatement("SELECT revision FROM saved_words").simpleQueryForLong())
                assertEquals(0L, database.openHelper.writableDatabase.compileStatement("SELECT COUNT(*) FROM word_review_events").simpleQueryForLong())
                assertEquals(0L, database.openHelper.writableDatabase.compileStatement("SELECT COUNT(*) FROM word_schedules").simpleQueryForLong())
                assertEquals(emptyList<String>(), database.bookCovers().storedFileNames())
                val first = cover()
                database.bookCovers().upsert(first.toCoverEntity(book.id, book.sha256))
                assertEquals(first, RoomLibraryRepository(database.books()).findById(book.id)!!.customCover)
                database.books().deleteExact(book.id, book.sha256)
                assertNull(database.bookCovers().find(book.id))
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }
}
