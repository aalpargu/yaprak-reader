package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.BookEntity
import com.yaprak.reader.data.persistence.BookNoteEntity
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomBookNoteRepository
import com.yaprak.reader.data.persistence.RoomLibraryNotesRepository
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.notes.LibraryNotesResult
import com.yaprak.reader.domain.notes.LibraryNotesSourceException
import com.yaprak.reader.domain.notes.MAX_LIBRARY_NOTE_RESULTS
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomLibraryNotesRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val epub = BookEntity("epub", "İstanbul IŞIK", "Çağrı Şen", "EPUB",
        "book.epub", "book.epub", 100, "a".repeat(64), 1, null)
    private val pdf = epub.copy(id = "pdf", title = "PDF başlığı", authors = null, format = "PDF",
        originalName = "book.pdf", storageName = "book.pdf", sha256 = "b".repeat(64), pageCount = 10)

    @Test fun `joined notes survive reopen`() = runBlocking {
        // Robolectric includes the method name in its Windows temp root; keep this path short.
        val name = "ln-${UUID.randomUUID().toString().take(8)}.db"
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            open().useDatabase { db ->
                db.books().insert(epub)
                db.books().insert(pdf)
                db.bookNotes().add(note("note-a", "  Unicode 🌿\nikinci satır  ", 7))
                db.bookNotes().add(note("note-b", "PDF son sayfa", 9, pdf, 9))
                db.bookNotes().add(note("stale", "Eski içerik", 99).copy(contentSha256 = "old-hash"))
            }
            open().useDatabase { db ->
                val repository = RoomLibraryNotesRepository(db)
                val result = repository.observe("").first()
                assertEquals(2, result.totalCount)
                assertEquals(listOf("note-b", "note-a"), result.entries.map { it.id })
                val first = result.entries.last()
                assertEquals("  Unicode 🌿\nikinci satır  ", first.text)
                assertEquals(epub.title, first.bookTitle)
                assertEquals(epub.authors, first.bookAuthors)
                assertEquals(epub.sha256, first.contentSha256)
                assertEquals(BookFormat.EPUB, first.bookFormat)
                assertNull(first.pdfPageIndex)
                val page = result.entries.first()
                assertEquals(BookFormat.PDF, page.bookFormat)
                assertNull(page.bookAuthors)
                assertEquals(9, page.pdfPageIndex)
                assertEquals(first, repository.find(epub.id, epub.sha256, first.id))
                assertNull(repository.find(pdf.id, pdf.sha256, first.id))
                assertNull(repository.find(epub.id, "old-hash", first.id))
                assertNull(repository.find(epub.id, epub.sha256, "stale"))
                assertNull(repository.find("book' OR 1=1 --", epub.sha256, first.id))
                assertNull(repository.find(epub.id, epub.sha256, "note-a' OR 1=1 --"))
                assertEquals(3L, count(db)) // Search and find never write saved records.
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    @Test fun `AND search spans note title author with Turkish case Unicode and literal punctuation`() = runBlocking {
        withDatabase { db ->
            db.books().insert(epub)
            db.books().insert(pdf)
            db.bookNotes().add(note("match", "Gece\nkar 🌿 yüzde% alt_ ' OR 1=1 --", 2))
            db.bookNotes().add(note("other", "Gece başka", 1, pdf))
            val repository = RoomLibraryNotesRepository(db)
            val before = count(db)
            val previousLocale = Locale.getDefault()
            try {
                for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"))) {
                    Locale.setDefault(locale)
                    for (query in listOf("ışık çağrı gece", "IŞIK İSTANBUL 🌿", "gece\tşen\u00a0kar")) {
                        assertEquals(query, listOf("match"), repository.observe(query).first().entries.map { it.id })
                    }
                }
            } finally { Locale.setDefault(previousLocale) }
            assertEquals(listOf("match"), repository.observe("% _").first().entries.map { it.id })
            assertEquals(listOf("match"), repository.observe("' OR 1=1 --").first().entries.map { it.id })
            assertEquals(0, repository.observe("ışık başka").first().totalCount)
            assertEquals(0, repository.observe("missing").first().totalCount)
            assertEquals(2, repository.observe(" \n\t\u00a0").first().totalCount)
            assertEquals(before, count(db))
        }
    }

    @Test fun `search never invents one term across separate saved fields`() = runBlocking {
        withDatabase { db ->
            db.books().insert(epub.copy(title = "cd", authors = "ef"))
            db.bookNotes().add(note("boundary", "ab", 1))
            val repository = RoomLibraryNotesRepository(db)
            assertEquals(0, repository.observe("abcd").first().totalCount)
            assertEquals(0, repository.observe("cdef").first().totalCount)
            assertEquals(1, repository.observe("ab cd ef").first().totalCount)
        }
    }

    @Test fun `over a thousand notes yield bounded newest results full count and older narrow matches`() = runBlocking {
        withDatabase { db ->
            db.books().insert(epub)
            db.withTransaction {
                repeat(1200) { index ->
                    val id = "note-${index.toString().padStart(4, '0')}"
                    db.bookNotes().add(note(id, if (index == 3) "eski eşsiz 🌿" else "Ortak not", index / 3L))
                }
            }
            val repository = RoomLibraryNotesRepository(db)
            val all = repository.observe("").first()
            assertEquals(1200, all.totalCount)
            assertEquals(MAX_LIBRARY_NOTE_RESULTS, all.entries.size)
            assertEquals((1199 downTo 1000).map { "note-${it.toString().padStart(4, '0')}" }, all.entries.map { it.id })
            val narrow = repository.observe("eşsiz").first()
            assertEquals(1, narrow.totalCount)
            assertEquals("note-0003", narrow.entries.single().id)
            assertEquals(1200L, count(db))
        }
    }

    @Test fun `live flow follows note edits metadata changes stale content and cascaded book removal`() = runBlocking {
        withDatabase { db ->
            db.books().insert(epub)
            db.books().insert(pdf)
            val repository = RoomLibraryNotesRepository(db)
            val updates = Channel<LibraryNotesResult>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { repository.observe("").collect { updates.send(it) } }
            suspend fun nextWhen(predicate: (LibraryNotesResult) -> Boolean): LibraryNotesResult = withTimeout(10_000) {
                var result = updates.receive()
                while (!predicate(result)) result = updates.receive()
                result
            }
            try {
                assertEquals(0, nextWhen { it.totalCount == 0 }.totalCount)
                val notes = RoomBookNoteRepository(db)
                notes.add(epub.id, epub.sha256, "İlk not")
                val original = nextWhen { it.entries.any { entry -> entry.text == "İlk not" } }.entries.single()
                notes.update(epub.id, epub.sha256, original.id, "Yeni not\n🌿")
                assertEquals(original.copy(text = "Yeni not\n🌿"),
                    nextWhen { it.entries.singleOrNull()?.text == "Yeni not\n🌿" }.entries.single())
                db.books().updateMetadataExact(epub.id, epub.sha256, "Güncel başlık", "Yeni yazar")
                val metadata = nextWhen { it.entries.singleOrNull()?.bookTitle == "Güncel başlık" }.entries.single()
                assertEquals("Yeni yazar", metadata.bookAuthors)
                assertEquals(metadata, repository.find(epub.id, epub.sha256, original.id))
                notes.add(pdf.id, pdf.sha256, "PDF notu")
                assertEquals(2, nextWhen { it.totalCount == 2 }.totalCount)
                notes.remove(epub.id, epub.sha256, original.id)
                assertEquals(pdf.id, nextWhen { it.totalCount == 1 }.entries.single().bookId)
                notes.add(epub.id, epub.sha256, "Yeni içerik öncesi")
                nextWhen { it.totalCount == 2 }
                db.withTransaction {
                    db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ? WHERE id = ?",
                        arrayOf("c".repeat(64), epub.id))
                }
                assertEquals(pdf.id, nextWhen { it.totalCount == 1 }.entries.single().bookId)
                assertNull(repository.find(epub.id, epub.sha256, original.id))
                db.books().deleteExact(pdf.id, pdf.sha256)
                assertEquals(0, nextWhen { it.totalCount == 0 }.totalCount)
                assertEquals(1L, count(db)) // Stale EPUB row stays stored; PDF FK cascade removes its row.
            } finally {
                collector.cancelAndJoin()
                updates.close()
            }
        }
    }

    @Test fun `invalid current text metadata format and PDF page reject the source without dropping records`() = runBlocking {
        withDatabase { db ->
            db.books().insert(epub)
            db.books().insert(pdf)
            val repository = RoomLibraryNotesRepository(db)
            for (invalidText in listOf("", " \n ", "x".repeat(2001))) {
                db.bookNotes().add(note("invalid", invalidText, 1))
                assertSourceFailure { repository.observe("will not match").first() }
                assertSourceFailure { repository.find(epub.id, epub.sha256, "invalid") }
                assertEquals(1L, count(db))
                db.bookNotes().remove(epub.id, epub.sha256, "invalid")
            }
            db.bookNotes().add(note("invalid", "Valid text", 1))
            for ((field, value) in listOf("title" to "x".repeat(1001), "authors" to "x".repeat(2001), "format" to "BAD")) {
                db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE books SET $field = ? WHERE id = ?",
                    arrayOf(value, epub.id)) }
                assertSourceFailure { repository.observe("").first() }
                db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE books SET title = ?, authors = ?, format = 'EPUB' WHERE id = ?",
                    arrayOf(epub.title, epub.authors, epub.id)) }
            }
            db.bookNotes().remove(epub.id, epub.sha256, "invalid")
            for ((book, page) in listOf(epub to 0, pdf to -1, pdf to 10)) {
                db.bookNotes().add(note("invalid", "Bad page", 1, book, page))
                assertSourceFailure { repository.observe("").first() }
                assertSourceFailure { repository.find(book.id, book.sha256, "invalid") }
                db.bookNotes().remove(book.id, book.sha256, "invalid")
            }
            db.bookNotes().add(note("invalid", "Valid text", 1, pdf))
            db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE books SET pageCount = NULL WHERE id = ?", arrayOf(pdf.id)) }
            assertSourceFailure { repository.observe("").first() }
            assertEquals(1L, count(db))
        }
    }

    @Test fun `startup and journal guard run off main before transaction and after queued mutex wait`() = runBlocking {
        withDatabase { db ->
            val ready = AtomicBoolean(false)
            val journal = AtomicBoolean(false)
            val calls = AtomicInteger(0)
            val mutex = Mutex()
            val repository = RoomLibraryNotesRepository(db, fileMutex = mutex, validateRead = {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                calls.incrementAndGet()
                check(ready.get())
                check(!journal.get())
            })
            assertFalse(db.isOpen)
            assertSourceFailure { repository.find(epub.id, epub.sha256, "missing") }
            assertFalse(db.isOpen)
            ready.set(true)
            journal.set(true)
            assertSourceFailure { repository.find(epub.id, epub.sha256, "missing") }
            assertFalse(db.isOpen)
            journal.set(false)
            mutex.lock()
            val started = CompletableDeferred<Unit>()
            val pending = async(Dispatchers.Default) {
                started.complete(Unit)
                runCatching { repository.find(epub.id, epub.sha256, "missing") }.exceptionOrNull()
            }
            started.await()
            ready.set(false)
            mutex.unlock()
            assertTrue(pending.await() is LibraryNotesSourceException)
            assertEquals(3, calls.get())
            assertFalse(db.isOpen)
            ready.set(true)
            assertNull(repository.find(epub.id, epub.sha256, "missing"))
            assertTrue(db.isOpen)
        }
    }

    @Test fun `cancellation while queued behind file lock skips guard and source query`() = runBlocking {
        withDatabase { db ->
            val calls = AtomicInteger(0)
            val mutex = Mutex(locked = true)
            val repository = RoomLibraryNotesRepository(db, fileMutex = mutex, validateRead = { calls.incrementAndGet() })
            val started = CompletableDeferred<Unit>()
            val pending = launch(Dispatchers.Default) {
                started.complete(Unit)
                repository.find(epub.id, epub.sha256, "missing")
            }
            started.await()
            pending.cancelAndJoin()
            mutex.unlock()
            assertTrue(pending.isCancelled)
            assertEquals(0, calls.get())
            assertFalse(db.isOpen)
        }
    }

    @Test fun `guarded observe rejects existing joined rows until startup and recovery are ready`() = runBlocking {
        withDatabase { db ->
            db.books().insert(epub)
            db.bookNotes().add(note("saved", "Kayıtlı not", 1))
            val ready = AtomicBoolean(false)
            val journal = AtomicBoolean(false)
            val repository = RoomLibraryNotesRepository(db, validateRead = {
                check(ready.get())
                check(!journal.get())
            })
            assertSourceFailure { repository.observe("").first() }
            ready.set(true)
            journal.set(true)
            assertSourceFailure { repository.observe("Kayıtlı").first() }
            journal.set(false)
            assertEquals("saved", repository.observe("").first().entries.single().id)
            assertEquals(1L, count(db))
        }
    }

    @Test fun `guard cancellation propagates and closed database is typed source failure`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = RoomLibraryNotesRepository(db, validateRead = { throw CancellationException("cancel guard") })
        assertTrue(runCatching { repository.find(epub.id, epub.sha256, "missing") }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { repository.observe("").first() }.exceptionOrNull() is CancellationException)
        db.books().insert(epub)
        db.close()
        assertSourceFailure("closed database find") { RoomLibraryNotesRepository(db).find(epub.id, epub.sha256, "missing") }
        assertFalse("A closed Room helper must not reopen after find", db.isOpen)
        assertSourceFailure("closed database observe") { RoomLibraryNotesRepository(db).observe("").first() }
        assertFalse("A closed Room helper must not reopen after observe", db.isOpen)
    }

    @Test
    @Config(sdk = [28])
    fun `joined read works before API35`() = runBlocking {
        withDatabase { db ->
            db.books().insert(epub)
            db.books().insert(pdf)
            db.bookNotes().add(note("epub-note", "Geçerli EPUB notu", 1))
            db.bookNotes().add(note("pdf-note", "Geçerli PDF sayfa notu", 2, pdf, 0))
            val repository = RoomLibraryNotesRepository(db)
            val result = repository.observe("").first()
            assertEquals(listOf("pdf-note", "epub-note"), result.entries.map { it.id })
            assertEquals(2, result.totalCount)
            assertEquals(result.entries.first(), repository.find(pdf.id, pdf.sha256, "pdf-note"))
            assertEquals(0, result.entries.first().pdfPageIndex)
            assertEquals(2L, count(db))
        }
    }

    @Test fun `cancelling a live observer stays cancellation rather than source failure`() = runBlocking {
        withDatabase { db ->
            db.books().insert(epub)
            db.bookNotes().add(note("saved", "Canlı not", 1))
            val first = CompletableDeferred<LibraryNotesResult>()
            val sourceFailure = AtomicBoolean(false)
            val collector = launch(Dispatchers.Default) {
                try {
                    RoomLibraryNotesRepository(db).observe("").collect { first.complete(it) }
                } catch (failure: LibraryNotesSourceException) {
                    sourceFailure.set(true)
                    throw failure
                }
            }
            try {
                assertEquals("saved", withTimeout(10_000) { first.await() }.entries.single().id)
                collector.cancelAndJoin()
                assertTrue(collector.isCancelled)
                assertFalse("A caller cancellation must not become an unavailable-source error", sourceFailure.get())
                assertEquals(1L, count(db))
            } finally { collector.cancelAndJoin() }
        }
    }

    private fun note(id: String, text: String, time: Long, book: BookEntity = epub, page: Int? = null) =
        BookNoteEntity(id, book.id, book.sha256, text, time, page)

    private fun count(db: LibraryDatabase) = db.openHelper.writableDatabase
        .compileStatement("SELECT count(*) FROM book_notes").simpleQueryForLong()

    private suspend fun assertSourceFailure(label: String = "source", block: suspend () -> Any?) {
        val failure = runCatching { withTimeout(10_000) { block() } }.exceptionOrNull()
        assertTrue("$label expected LibraryNotesSourceException, got ${failure?.javaClass?.name}: ${failure?.message}",
            failure is LibraryNotesSourceException)
    }

    private suspend fun withDatabase(block: suspend (LibraryDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }
}
