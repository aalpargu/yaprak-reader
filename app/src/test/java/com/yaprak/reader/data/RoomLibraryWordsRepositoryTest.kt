package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.vocabulary.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomLibraryWordsRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = BookEntity("one", "İstanbul IŞIK", "Çağrı Şen", "EPUB", "one.epub", "one.epub", 1, "a".repeat(64), 1, null)
    private val other = book.copy(id = "two", title = "Diğer kitap", authors = null, sha256 = "b".repeat(64))
    private suspend fun database(test: suspend (LibraryDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try { db.books().insert(book); db.books().insert(other); test(db) } finally { db.close() }
    }
    private fun word(id: String, english: String = "term", turkish: String = "anlam", time: Long = 1, owner: BookEntity = book) =
        SavedWordEntity(id, owner.id, owner.sha256, english, turkish, time)

    @Test fun `search spans all current books and Turkish fields before limit preserving literal punctuation and exact source identity`() = runBlocking {
        database { db ->
            db.withTransaction {
                repeat(210) { db.savedWords().add(word("new-$it", time = 10L + it, owner = other)) }
                db.savedWords().add(word("match", "DI🌿 yüzde% alt_", "Gece kar", 2))
                db.savedWords().add(word("stale", time = 999).copy(contentSha256 = "c".repeat(64)))
            }
            val source = RoomLibraryWordsRepository(db)
            val all = source.observe("").first()
            assertEquals(211, all.totalCount); assertEquals(200, all.entries.size)
            assertEquals("new-209", all.entries.first().word.id)
            for (query in listOf("ışık çağrı gece", "ISTANBUL DI🌿 kar", "% _", "İŞIK ŞEN"))
                assertEquals(listOf("match"), source.observe(query).first().entries.map { it.word.id })
            assertEquals(0, source.observe("istanbul missing").first().totalCount)
            val exact = source.find(book.id, book.sha256, "match")!!
            assertEquals(book.title, exact.bookTitle); assertEquals("Gece kar", exact.word.turkish)
            assertNull(source.find(other.id, other.sha256, "match"))
            assertNull(source.find(book.id, book.sha256, "stale"))
            assertNull(source.find(book.id, book.sha256, "' OR 1=1 --"))
            assertEquals(212L, db.openHelper.writableDatabase.compileStatement("SELECT count(*) FROM saved_words").simpleQueryForLong())
        }
    }

    @Test fun `metadata edits word edits and deletion are seen in current content snapshots without changing records`() = runBlocking {
        database { db ->
            db.savedWords().add(word("saved"))
            val source = RoomLibraryWordsRepository(db)
            val before = source.observe("").first().entries.single()
            assertEquals(1, db.books().updateMetadataExact(book.id, book.sha256, "Yeni başlık", "Yeni yazar"))
            assertEquals(1, db.savedWords().update(book.id, book.sha256, "saved", "edited", "düzenlendi"))
            val next = source.observe("yeni edited").first().entries.single()
            assertEquals("Yeni başlık", next.bookTitle); assertEquals("düzenlendi", next.word.turkish)
            assertNotEquals(before, next)
            assertEquals(1, db.books().deleteExact(book.id, book.sha256))
            assertEquals(0, source.observe("").first().totalCount)
            assertNull(source.find(book.id, book.sha256, "saved"))
        }
    }

    @Test fun `closed or malformed source is failure and a cancelled mutex waiter stays cancelled`() = runBlocking {
        database { db ->
            db.savedWords().add(word("bad", english = "bad\nline"))
            assertTrue(runCatching { RoomLibraryWordsRepository(db).observe("").first() }.exceptionOrNull() is LibraryWordsSourceException)
            db.savedWords().remove(book.id, book.sha256, "bad")
            val mutex = Mutex(locked = true)
            val source = RoomLibraryWordsRepository(db, fileMutex = mutex)
            val waiter = async { source.observe("").first() }
            yield(); waiter.cancelAndJoin()
            assertTrue(waiter.isCancelled); mutex.unlock()
            assertEquals(0, source.observe("").first().totalCount)
            db.close()
            assertTrue(runCatching { source.observe("").first() }.exceptionOrNull() is LibraryWordsSourceException)
        }
    }

    @Test fun `query clipping preserves supplementary Unicode and a recovery guard is rechecked after waiting`() = runBlocking {
        assertEquals("a".repeat(199), limitLibraryWordQuery("a".repeat(199) + "😀suffix"))
        database { db ->
            val mutex = Mutex(locked = true)
            var allowed = true
            val source = RoomLibraryWordsRepository(db, fileMutex = mutex, validateRead = { check(allowed) })
            val waiter = async { runCatching { source.observe("").first() } }
            yield(); allowed = false; mutex.unlock()
            assertTrue(waiter.await().exceptionOrNull() is LibraryWordsSourceException)
        }
    }
}
