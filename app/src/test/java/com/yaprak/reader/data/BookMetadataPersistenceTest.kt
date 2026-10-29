package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.ReadingProgressEntity
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BookMetadataPersistenceTest {
    @Test fun `metadata update matches id and content hash without changing other book or reading fields`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val repository = RoomLibraryRepository(db.books())
            val original = Book("target", "Imported title", "Imported author", BookFormat.PDF,
                "source.pdf", "target.pdf", 1234, "current-hash", 42, pageCount = 17)
            val other = Book("other", "Other title", null, BookFormat.EPUB,
                "other.epub", "other.epub", 456, "other-hash", 43)
            repository.add(original)
            repository.add(other)
            val progress = ReadingProgressEntity(original.id, original.sha256, 1,
                "{\"page\":4}", "page:4", 987)
            db.reading().save(progress)

            assertFalse(repository.updateMetadata(original.id, "stale-hash", "Wrong", null))
            assertFalse(repository.updateMetadata("missing", original.sha256, "Wrong", null))
            assertEquals(original, repository.findById(original.id))

            assertTrue(repository.updateMetadata(original.id, original.sha256, "My title", "My author"))
            val edited = original.copy(title = "My title", authors = "My author")
            assertEquals(edited, repository.findById(original.id))
            assertEquals(progress, db.reading().resume(original.id, original.sha256))
            assertEquals(mapOf(edited.id to edited.copy(lastReadAt = progress.updatedAt),
                other.id to other), repository.observeBooks().first().associateBy(Book::id))

            assertTrue(repository.updateMetadata(original.id, original.sha256, "My title", null))
            assertEquals(edited.copy(authors = null), repository.findById(original.id))
            assertEquals(progress, db.reading().resume(original.id, original.sha256))
        }
        Unit
    }
}
