package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportedOrganizationPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("book", "Kitap", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "a".repeat(64), 1)
    private val initial = BookOrganization(series = "Seri", tags = listOf("Konu"), collections = listOf("Raf"))
    private suspend fun <T> using(block: suspend (LibraryDatabase, RoomLibraryRepository) -> T): T {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        return try { block(db, RoomLibraryRepository(db.books())) } finally { db.close() }
    }
    @Test fun failedOrganizationInsertRollsBackNewBookAndRetryCommitsBoth() = runBlocking { using { db, repo ->
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER refuse_import BEFORE INSERT ON book_organizations BEGIN SELECT RAISE(ABORT, 'controlled'); END")
        assertNotNull(runCatching { repo.addImported(book, initial) }.exceptionOrNull())
        assertNull(repo.findById(book.id)); assertTrue(repo.storedFileNames().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER refuse_import")
        repo.addImported(book, initial)
        assertEquals(initial, repo.findById(book.id)!!.organization)
        assertEquals(0, db.books().completionEventCount())
    } }
    @Test fun importedStateFavoriteAndInvalidLabelsAreRejectedBeforeBookInsertion() = runBlocking { using { _, repo ->
        for (invalid in listOf(initial.copy(readingStatus = BookReadingStatus.COMPLETED), initial.copy(favorite = true),
            initial.copy(tags = listOf("bad\nname")))) {
            assertNotNull(runCatching { repo.addImported(book, invalid) }.exceptionOrNull())
            assertNull(repo.findById(book.id))
        }
    } }
    @Test fun conflictingBookHashOrIdNeverReplacesPreviouslyImportedLabels() = runBlocking { using { db, repo ->
        repo.addImported(book, initial)
        for (duplicate in listOf(book.copy(title = "Replacement"), book.copy(id = "other", storageName = "other.epub"))) {
            assertNotNull(runCatching { repo.addImported(duplicate, initial.copy(series = "Replacement")) }.exceptionOrNull())
            assertEquals(initial, repo.findById(book.id)!!.organization)
            assertEquals("Kitap", repo.findById(book.id)!!.title)
        }
        assertEquals(0, db.books().completionEventCount())
    } }
    @Test fun emptyImportedMetadataAndExistingAddDoNotInheritReadingState() = runBlocking { using { db, repo ->
        repo.addImported(book, BookOrganization())
        repo.add(book.copy(id = "legacy", sha256 = "b".repeat(64),
            organization = initial.copy(readingStatus = BookReadingStatus.READING, favorite = true)))
        assertEquals(BookOrganization(), repo.findById(book.id)!!.organization)
        assertEquals(BookOrganization(), repo.findById("legacy")!!.organization)
        assertEquals(0L, db.openHelper.writableDatabase.compileStatement("SELECT COUNT(*) FROM book_organizations").simpleQueryForLong())
        assertEquals(0, db.books().completionEventCount())
    } }
}
