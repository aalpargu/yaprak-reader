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
import com.yaprak.reader.feature.library.LibrarySort
import com.yaprak.reader.feature.library.sortBooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomPersistenceTest {
    @Test fun `books survive database reopen and sort newest first`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "persistence-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        val first = Book("id1", "First", "Author", BookFormat.EPUB, "source.epub", "id1.epub", 100, "hash1", 1)
        val second = first.copy(id = "id2", title = "Second", sha256 = "hash2", storageName = "id2.epub", importedAt = 2)
        open().useDatabase { db ->
            val repository = RoomLibraryRepository(db.books())
            repository.add(first)
            repository.add(second)
        }
        open().useDatabase { db ->
            val repository = RoomLibraryRepository(db.books())
            assertEquals(listOf(second, first), repository.observeBooks().first())
            assertEquals(first, repository.findByHash("hash1"))
            assertEquals(setOf("id1.epub", "id2.epub"), repository.storedFileNames())
            val error = runCatching { repository.add(first.copy(id = "another-id")) }.exceptionOrNull()
            assertNotNull("Unique hash index must reject duplicate content", error)
            assertEquals(2, repository.observeBooks().first().size)
        }
        context.deleteDatabase(name)
        Unit
    }

    @Test fun `active library flow follows saved positions and ignores a stale content hash`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val repository = RoomLibraryRepository(db.books())
            val newest = Book("newest", "New", null, BookFormat.PDF,
                "newest.pdf", "newest.pdf", 1, "new-hash", 30, 2)
            val middle = Book("middle", "Middle", null, BookFormat.EPUB,
                "middle.epub", "middle.epub", 1, "middle-hash", 20)
            val oldest = Book("oldest", "Old", null, BookFormat.EPUB,
                "oldest.epub", "oldest.epub", 1, "old-hash", 10)
            listOf(oldest, newest, middle).forEach { repository.add(it) }

            val updates = Channel<List<Book>>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) {
                repository.observeBooks().collect { updates.send(it) }
            }
            suspend fun next() = withTimeout(10_000) { updates.receive() }
            fun sortedIds(books: List<Book>) = sortBooks(books, LibrarySort.LAST_READ).map(Book::id)
            try {
                assertEquals(listOf("newest", "middle", "oldest"), sortedIds(next()))

                db.reading().save(ReadingProgressEntity(oldest.id, "stale-hash", 1, "{}", "chapter", 900))
                val stale = next()
                assertNull(stale.first { it.id == oldest.id }.lastReadAt)
                assertEquals(listOf("newest", "middle", "oldest"), sortedIds(stale))

                db.reading().save(ReadingProgressEntity(oldest.id, oldest.sha256, 1, "{}", "chapter", 100))
                assertEquals(listOf("oldest", "newest", "middle"), sortedIds(next()))

                db.reading().save(ReadingProgressEntity(newest.id, newest.sha256, 1, "{}", "page:0", 200))
                assertEquals(listOf("newest", "oldest", "middle"), sortedIds(next()))

                db.reading().save(ReadingProgressEntity(oldest.id, oldest.sha256, 1, "{}", "chapter", 300))
                assertEquals(listOf("oldest", "newest", "middle"), sortedIds(next()))
            } finally {
                collector.cancelAndJoin()
                updates.close()
            }
        }
        Unit
    }
}

internal inline fun <T> LibraryDatabase.useDatabase(block: (LibraryDatabase) -> T): T =
    try { block(this) } finally { close() }
