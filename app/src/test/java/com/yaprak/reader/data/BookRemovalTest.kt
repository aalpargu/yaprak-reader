package com.yaprak.reader.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.removing.DefaultBookRemover
import com.yaprak.reader.data.storage.BookOperationCoordinator
import com.yaprak.reader.data.storage.BookFileCleanup
import com.yaprak.reader.data.storage.LocalBookFiles
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookRemovalResult
import com.yaprak.reader.reader.ReaderLoader
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import com.yaprak.reader.domain.reader.ReadingLocation
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookRemovalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val book = Book("book", "Kitap", "Yazar", BookFormat.EPUB,
        "source.epub", "book.epub", 4, "hash-one", 1)

    @Test fun `confirmed removal cascades all book records and deletes only its private copy`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directory = temporary.newFolder("books")
        val original = temporary.newFile("source.epub").apply { writeText("source") }
        val privateCopy = File(directory, book.storageName).apply { writeText("copy") }
        val other = book.copy(id = "other", sha256 = "hash-two", storageName = "other.epub")
        val otherCopy = File(directory, other.storageName).apply { writeText("other") }
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            db.books().insert(book.toEntity())
            db.books().insert(other.toEntity())
            db.reading().save(ReadingProgressEntity(book.id, book.sha256, 1, "{}", "chapter", 1))
            db.reading().observe(listOf(ObservedTextEntity(book.id, book.sha256, 1, "chapter", "0", 0, 1, "a".repeat(64))))
            db.bookmarks().add(BookmarkEntity("bookmark", book.id, book.sha256, "location", "Mark", "{}", "chapter", 1, 1))
            db.bookThemes().save(BookEpubThemeOverrideEntity(book.id, book.sha256, "DARK"))
            db.bookFontSizes().save(BookEpubFontSizeOverrideEntity(book.id, book.sha256, 100))
            db.bookFonts().save(BookEpubFontOverrideEntity(book.id, book.sha256, "SERIF"))
            db.bookMargins().save(BookEpubMarginOverrideEntity(book.id, book.sha256, 100))
            db.bookLineHeights().save(BookEpubLineSpacingOverrideEntity(book.id, book.sha256, 120))
            db.savedWords().add(SavedWordEntity("word", book.id, book.sha256, "leaf", "yaprak", 1))
            db.bookNotes().add(BookNoteEntity("note", book.id, book.sha256, "note", 1))
            db.epubPassages().add(EpubPassageEntity("passage", book.id, book.sha256, 1, "chapter", "a".repeat(64), 0, 1, "x", 1))
            db.readingDurations().insert(ReadingDurationReceiptEntity("11111111-1111-4111-8111-111111111111",
                book.id, book.sha256, "1970-01-01", "UTC", 0, 1000, 1000))

            val remover = DefaultBookRemover(db, LocalBookFiles(directory), BookOperationCoordinator())
            assertEquals(BookRemovalResult.Removed(), remover.remove(book.id, book.sha256))
            assertNull(db.books().findById(book.id))
            assertEquals(other.toEntity(), db.books().findById(other.id))
            assertFalse(privateCopy.exists())
            assertTrue(otherCopy.exists())
            assertTrue(original.exists())
            val tables = listOf("reading_progress", "observed_text", "bookmarks",
                "book_epub_theme_overrides", "book_epub_font_size_overrides", "book_epub_font_overrides",
                "book_epub_margin_overrides", "book_epub_line_spacing_overrides", "saved_words",
                "book_notes", "epub_passages", "reading_duration_receipts")
            val sql = db.openHelper.readableDatabase
            tables.forEach { table ->
                sql.query("SELECT COUNT(*) FROM $table WHERE bookId = '${book.id}'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("$table must cascade", 0, cursor.getInt(0))
                }
            }
            assertEquals(BookRemovalResult.Missing, remover.remove(book.id, book.sha256))
        }
    }

    @Test fun `stale identity and unsafe stored path never delete an unrelated file`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directory = temporary.newFolder("books")
        val outside = temporary.newFile("outside.epub").apply { writeText("keep") }
        val damaged = book.copy(storageName = "../outside.epub")
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            db.books().insert(damaged.toEntity())
            val remover = DefaultBookRemover(db, LocalBookFiles(directory), BookOperationCoordinator())
            assertEquals(BookRemovalResult.Missing, remover.remove(book.id, "wrong-hash"))
            assertNotNull(db.books().findById(book.id))
            assertEquals(BookRemovalResult.Removed(privateFileMayRemain = true), remover.remove(book.id, book.sha256))
            assertNull(db.books().findById(book.id))
            assertTrue(outside.exists())
        }
    }

    @Test fun `a shared stored file survives until its last database reference is removed`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directory = temporary.newFolder("books")
        val sharedCopy = File(directory, book.storageName).apply { writeText("copy") }
        val other = book.copy(id = "other", sha256 = "hash-two")
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            db.books().insert(book.toEntity())
            db.books().insert(other.toEntity())
            val remover = DefaultBookRemover(db, LocalBookFiles(directory), BookOperationCoordinator())
            assertEquals(BookRemovalResult.Removed(), remover.remove(book.id, book.sha256))
            assertNull(db.books().findById(book.id))
            assertNotNull(db.books().findById(other.id))
            assertTrue(sharedCopy.exists())
            assertEquals(BookRemovalResult.Removed(), remover.remove(other.id, other.sha256))
            assertFalse(sharedCopy.exists())
        }
    }

    @Test fun `open reader prevents removal until session closes`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directory = temporary.newFolder("books")
        val privateCopy = File(directory, book.storageName).apply { writeText("copy") }
        val operations = BookOperationCoordinator()
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            db.books().insert(book.toEntity())
            var closed = 0
            val reader = operations.track(ReaderLoader {
                object : ReaderSession {
                    override val book = this@BookRemovalTest.book
                    override val resume: ReadingLocation? = null
                    override fun createSurface(initial: ReadingLocation?, dark: Boolean,
                        onTap: () -> Unit, onError: () -> Unit): ReaderSurface = error("Unused")
                    override fun close() { closed++ }
                }
            })
            val session = reader.open(book.id)
            val remover = DefaultBookRemover(db, LocalBookFiles(directory), operations)
            assertEquals(BookRemovalResult.InUse, remover.remove(book.id, book.sha256))
            assertNotNull(db.books().findById(book.id))
            assertTrue(privateCopy.exists())
            session.close()
            session.close()
            assertEquals(1, closed)
            assertEquals(BookRemovalResult.Removed(), remover.remove(book.id, book.sha256))
        }
    }

    @Test fun `database delete failure leaves book and private file intact`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directory = temporary.newFolder("books")
        val privateCopy = File(directory, book.storageName).apply { writeText("copy") }
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            db.books().insert(book.toEntity())
            db.bookmarks().add(BookmarkEntity("bookmark", book.id, book.sha256, "location", "Mark", "{}", "chapter", 1, 1))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_delete BEFORE DELETE ON books BEGIN SELECT RAISE(ABORT, 'blocked'); END")
            val localFiles = LocalBookFiles(directory)
            var cleanupCalls = 0
            val remover = DefaultBookRemover(db, BookFileCleanup { storageName ->
                cleanupCalls++
                localFiles.removeCommitted(storageName)
            }, BookOperationCoordinator())
            assertEquals(BookRemovalResult.Failed, remover.remove(book.id, book.sha256))
            assertNotNull(db.books().findById(book.id))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM bookmarks WHERE bookId = '${book.id}'").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            assertEquals(0, cleanupCalls)
            assertTrue(privateCopy.exists())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER block_delete")
            assertEquals(BookRemovalResult.Removed(), remover.remove(book.id, book.sha256))
            assertEquals(1, cleanupCalls)
            assertNull(db.books().findById(book.id))
            assertFalse(privateCopy.exists())
        }
    }

    @Test fun `failed unlink reports pending copy and orphan recovery deletes it later`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directory = temporary.newFolder("books")
        val privateCopy = File(directory, book.storageName).apply { writeText("copy") }
        val other = book.copy(id = "other", sha256 = "hash-two", storageName = "other.epub")
        val otherCopy = File(directory, other.storageName).apply { writeText("other") }
        val localFiles = LocalBookFiles(directory)
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            db.books().insert(book.toEntity())
            db.books().insert(other.toEntity())
            val remover = DefaultBookRemover(db, BookFileCleanup { false }, BookOperationCoordinator())
            assertEquals(BookRemovalResult.Removed(privateFileMayRemain = true), remover.remove(book.id, book.sha256))
            assertNull(db.books().findById(book.id))
            assertTrue(privateCopy.exists())
            localFiles.recover(db.books().storedFileNames().toSet())
            assertFalse(privateCopy.exists())
            assertNotNull(db.books().findById(other.id))
            assertTrue(otherCopy.exists())
        }
    }

    @Test fun `unlink exception leaves a recoverable orphan after committed deletion`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directory = temporary.newFolder("books")
        val privateCopy = File(directory, book.storageName).apply { writeText("copy") }
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            db.books().insert(book.toEntity())
            val remover = DefaultBookRemover(db, BookFileCleanup { throw IOException("unlink failed") }, BookOperationCoordinator())
            assertEquals(BookRemovalResult.Removed(privateFileMayRemain = true), remover.remove(book.id, book.sha256))
            assertNull(db.books().findById(book.id))
            assertTrue(privateCopy.exists())
            LocalBookFiles(directory).recover(db.books().storedFileNames().toSet())
            assertFalse(privateCopy.exists())
        }
    }
}
