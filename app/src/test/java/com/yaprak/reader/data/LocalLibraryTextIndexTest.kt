package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.ReadingProgressEntity
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.data.search.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocalLibraryTextIndexTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("a", "İstanbul", "Yazar", BookFormat.EPUB, "a.epub", "a.epub", 100, "a".repeat(64), 1)
    private fun document(book: Book, text: String = "  Dependency injection 🌿\nİstanbul IŞIK  ", count: Int = 1) =
        ExtractedBookText(book.id, book.sha256, (0 until count).map {
            ExtractedTextChunk(0, it, "chapter.xhtml", "c".repeat(64), "Bölüm bir", text)
        }, 1)

    private suspend fun fixture(callback: RoomDatabase.QueryCallback? = null,
        block: suspend (LibraryDatabase, TextIndexDatabase, RoomLibraryRepository) -> Unit) {
        val main = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val builder = Room.inMemoryDatabaseBuilder(context, TextIndexDatabase::class.java)
        if (callback != null) builder.setQueryCallback(callback, Executor { it.run() })
        val index = builder.build()
        try { val library = RoomLibraryRepository(main.books()); library.add(book); block(main, index, library) }
        finally { index.close(); main.close() }
    }

    @Test fun `real FTS finds current EPUB body text across books and preserves original context and progress`() = runBlocking {
        fixture { main, cache, library ->
            val second = book.copy(id = "b", sha256 = "b".repeat(64), title = "Second", storageName = "b.epub")
            val pdf = book.copy(id = "pdf", sha256 = "d".repeat(64), format = BookFormat.PDF, pageCount = 9)
            library.add(second); library.add(pdf)
            val progress = ReadingProgressEntity(book.id, book.sha256, 1, "{\"href\":\"chapter.xhtml\"}", "chapter.xhtml", 123)
            main.reading().save(progress)
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it) })
            val updates = mutableListOf<LibraryTextIndexProgress>()
            service.rebuild(updates::add)
            val result = service.observe("DEPENDENCY injection").first()
            assertEquals(2, result.totalMatches)
            assertEquals(setOf(book.id, second.id), result.hits.map { it.book.id }.toSet())
            val first = result.hits.first { it.book.id == book.id }
            assertEquals(document(book).chunks.single().text, first.text)
            assertEquals(first.text, first.snippet)
            assertEquals("Bölüm bir", first.chapterTitle)
            assertEquals("chapter.xhtml", first.target.resourceHref)
            assertEquals("c".repeat(64), first.target.resourceSha256)
            assertEquals(123L, first.book.lastReadAt)
            assertEquals(first, service.resolve(first))
            assertEquals(LibraryTextBookState.UNSUPPORTED, result.books.single { it.book.id == "pdf" }.state)
            assertEquals(2, service.observe("istanbul ışık").first().totalMatches)
            assertEquals(0, service.observe("İstanbul unknown").first().totalMatches)
            assertEquals(progress, main.reading().resume(book.id, book.sha256))
            assertEquals(3, updates.last().processed)
            assertNull(updates.last().currentBookTitle)
            assertFalse(main.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name LIKE 'text_index%'").use { it.moveToFirst() })
        }
    }

    @Test fun `FTS operators are literal words and punctuation never becomes a SQL expression`() = runBlocking {
        fixture { _, cache, library ->
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor {
                document(it, "OR NEAR marker percent% quote' café Dependency")
            })
            service.rebuild {}
            assertEquals(1, service.observe("OR NEAR").first().totalMatches)
            assertEquals(0, service.observe("marker OR missing").first().totalMatches)
            assertEquals(0, service.observe("marker' OR 1=1 --").first().totalMatches)
            assertEquals(1, service.observe("percent%").first().totalMatches)
            assertEquals(1, service.observe("café").first().totalMatches)
            assertEquals(0, service.observe("cafe").first().totalMatches)
            assertFailure(LibraryTextIndexFailure.INVALID_QUERY) { service.observe("*").first() }
            assertFailure(LibraryTextIndexFailure.INVALID_QUERY) { service.observe("a".repeat(201)).first() }
            assertFailure(LibraryTextIndexFailure.INVALID_QUERY) { service.observe((1..17).joinToString(" ") { "x$it" }).first() }
        }
    }

    @Test fun `derived FTS and original snippets survive close and reopen without reading the book again`() = runBlocking {
        val name = "txt-${UUID.randomUUID().toString().take(8)}.db"
        val main = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val library = RoomLibraryRepository(main.books()); library.add(book)
        fun open() = Room.databaseBuilder(context, TextIndexDatabase::class.java, name).build()
        var cache = open()
        try {
            LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it) }).rebuild {}
            cache.close(); cache = open()
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { error("Search must use the committed index") })
            val hit = service.observe("injection").first().hits.single()
            assertEquals(document(book).chunks.single().text, hit.text)
            assertEquals(hit, service.resolve(hit))
        } finally { cache.close(); main.close(); context.deleteDatabase(name) }
    }

    @Test fun `deleted changed and stale generations cannot fill the result cap or resolve old clicks`() = runBlocking {
        fixture { main, cache, library ->
            val deleted = book.copy(id = "0deleted", sha256 = "d".repeat(64))
            library.add(deleted)
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it, "matching", 201) })
            service.rebuild {}
            main.books().deleteExact(deleted.id, deleted.sha256)
            val result = service.observe("matching").first()
            assertEquals(201, result.totalMatches); assertEquals(200, result.hits.size); assertTrue(result.truncated)
            assertTrue(result.hits.all { it.book.id == book.id })
            val old = result.hits.first()
            service.rebuild {}
            assertNull(service.resolve(old))
            val next = service.observe("matching").first().hits.first()
            assertNotEquals(old.generation, next.generation)
            main.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ? WHERE id = ?", arrayOf("e".repeat(64), book.id))
            assertEquals(0, service.observe("matching").first().totalMatches)
            assertEquals(LibraryTextBookState.NOT_INDEXED, service.observe("").first().books.single().state)
            assertNull(service.resolve(next))
        }
    }

    @Test fun `metadata changes reject old clicks and refreshed results use the current title`() = runBlocking {
        fixture { _, cache, library ->
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it) })
            service.rebuild {}; val old = service.observe("injection").first().hits.single()
            library.updateMetadata(book.id, book.sha256, "Changed", null)
            assertNull(service.resolve(old))
            val refreshed = service.observe("injection").first().hits.single()
            assertEquals("Changed", refreshed.book.title); assertEquals(refreshed, service.resolve(refreshed))
            assertNull(service.resolve(refreshed.copy(target = refreshed.target.copy(resourceSha256 = "f".repeat(64)))))
            assertNull(service.resolve(refreshed.copy(text = "Forged source")))
        }
    }

    @Test fun `malformed persisted cache rows fail explicitly and rebuilding repairs only derived data`() = runBlocking {
        fixture { main, cache, library ->
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it) })
            for ((field, value) in listOf<Pair<String, Any>>("text" to "Dependency\u0000 invalid", "chapterTitle" to "x".repeat(MAX_INDEX_CHAPTER_TITLE + 1),
                "readingOrderIndex" to MAX_INDEX_BOOK_RESOURCES, "chunkIndex" to MAX_INDEX_BOOK_CHUNKS,
                "generation" to "invalid-generation", "readingOrderIndex" to 1.5)) {
                service.rebuild {}; val old = service.observe("dependency").first().hits.single()
                cache.openHelper.writableDatabase.execSQL("UPDATE text_index_chunks SET $field = ? WHERE rowid = ?", arrayOf<Any>(value, old.id))
                if (field == "text") cache.openHelper.writableDatabase.execSQL(
                    "UPDATE text_index_chunks SET searchText = ? WHERE rowid = ?", arrayOf<Any>(libraryTextSearchKey(value as String), old.id))
                assertFailure(LibraryTextIndexFailure.SOURCE) { service.resolve(old) }
                service.rebuild {}; assertEquals(1, service.observe("dependency").first().totalMatches)
                assertEquals(book, library.findById(book.id))
                assertEquals(1, main.books().observeAll().first().size)
            }
        }
    }

    @Test fun `invalid persisted ledger cannot bypass quotas and explicit rebuild regenerates it`() = runBlocking {
        fixture { _, cache, library ->
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it) })
            service.rebuild {}
            for ((field, value) in listOf<Pair<String, Any>>("stringBytes" to -1L, "chunkCount" to MAX_INDEX_BOOK_CHUNKS + 1,
                "generation" to "not-a-uuid", "stringBytes" to 1.5)) {
                cache.openHelper.writableDatabase.execSQL("UPDATE text_index_books SET $field = ? WHERE bookId = ?", arrayOf<Any>(value, book.id))
                assertFailure(LibraryTextIndexFailure.SOURCE) { service.observe("injection").first() }
                service.rebuild {}
                assertEquals(1, service.observe("injection").first().totalMatches)
            }
        }
    }

    @Test fun `cancelling an unfinished extraction preserves its previous committed generation`() = runBlocking {
        fixture { _, cache, library ->
            val entered = CompletableDeferred<Unit>(); var pause = false
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor {
                if (pause) { entered.complete(Unit); awaitCancellation() } else document(it)
            })
            service.rebuild {}; val old = service.observe("injection").first().hits.single()
            pause = true
            val job = launch(Dispatchers.Default) { service.rebuild {} }
            withTimeout(5000) { entered.await() }; job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(old, service.observe("injection").first().hits.single())
            assertEquals(old, service.resolve(old))
        }
    }

    @Test fun `an extractor cancellation remains cancellation even while its caller is active`() = runBlocking {
        fixture { _, cache, library ->
            var cancelled = false
            val original = CancellationException("controlled extraction cancellation")
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor {
                if (cancelled) throw original else document(it)
            })
            service.rebuild {}; val old = service.observe("injection").first().hits.single()
            cancelled = true
            try { service.rebuild {}; fail("Cancellation was swallowed") }
            catch (failure: CancellationException) {
                // Coroutine debug stack recovery may copy the exception while retaining its cause.
                assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === original })
                assertTrue(currentCoroutineContext().isActive)
            }
            assertEquals(old, service.observe("injection").first().hits.single())
        }
    }

    @Test fun `a native SQL failure after the first batch rolls back book and FTS publication together`() = runBlocking {
        fixture { _, cache, library ->
            var count = 1
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it, "stable", count) })
            service.rebuild {}; val old = service.observe("stable").first().hits.single()
            cache.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_late_chunk BEFORE INSERT ON text_index_chunks
                WHEN NEW.chunkIndex >= 100 BEGIN SELECT RAISE(ABORT, 'controlled derived-cache failure'); END""")
            count = 101
            assertFailure(LibraryTextIndexFailure.SOURCE) { service.rebuild {} }
            assertEquals(old, service.observe("stable").first().hits.single())
            assertEquals(1, cache.index().books().single().chunkCount)
            cache.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_late_chunk")
        }
    }

    @Test fun `original caller cancellation during the second native batch rolls back its unfinished generation`() = runBlocking {
        var running: Job? = null
        var cancelPublication = false
        val inserts = AtomicInteger()
        val callback = object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                if (cancelPublication && sqlQuery.contains("INSERT", ignoreCase = true) &&
                    sqlQuery.contains("text_index_chunks") && inserts.incrementAndGet() == 101) {
                    running!!.cancel(CancellationException("controlled caller cancellation during native publication"))
                }
            }
        }
        fixture(callback) { _, cache, library ->
            var count = 1
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it, "stable", count) })
            service.rebuild {}; val old = service.observe("stable").first().hits.single()
            count = 150; cancelPublication = true
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { service.rebuild {} }
            running = job; job.start(); withTimeout(5000) { job.join() }
            assertTrue("Query callback must reach the second batch", inserts.get() >= 101)
            assertTrue(job.isCancelled)
            cancelPublication = false
            assertEquals(old, service.observe("stable").first().hits.single())
            assertEquals(1, cache.index().books().single().chunkCount)
        }
    }

    @Test fun `source failures and limits are visible and never publish partial searchable text`() = runBlocking {
        fixture { _, cache, library ->
            var failure: TextExtractionFailure? = null
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor {
                failure?.let { reason -> throw TextExtractionException(reason) }; document(it)
            })
            service.rebuild {}
            for ((reason, state) in listOf(TextExtractionFailure.SOURCE to LibraryTextBookState.FAILED,
                TextExtractionFailure.LIMIT to LibraryTextBookState.LIMIT,
                TextExtractionFailure.UNSUPPORTED to LibraryTextBookState.UNSUPPORTED)) {
                failure = reason; service.rebuild {}
                val result = service.observe("injection").first()
                assertEquals(0, result.totalMatches); assertEquals(state, result.books.single().state)
            }
            failure = null; service.rebuild {}; assertEquals(1, service.observe("injection").first().totalMatches)
        }
    }

    @Test fun `queued cancellation skips source guard and a second rebuild cannot start another extraction`() = runBlocking {
        fixture { _, cache, library ->
            val mutex = Mutex(locked = true); val guards = AtomicInteger()
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it) }, mutex, {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper()); guards.incrementAndGet()
            })
            val queued = launch(Dispatchers.Default) { service.rebuild {} }
            yield(); queued.cancelAndJoin(); mutex.unlock(); assertEquals(0, guards.get())
            val entered = CompletableDeferred<Unit>()
            val blocking = LocalLibraryTextIndex(cache, library, BookTextExtractor {
                entered.complete(Unit); awaitCancellation()
            })
            val running = launch(Dispatchers.Default) { blocking.rebuild {} }
            withTimeout(5000) { entered.await() }
            assertFailure(LibraryTextIndexFailure.BUSY) { blocking.rebuild {} }
            running.cancelAndJoin()
        }
    }

    @Test fun `recovery guard and a closed cache fail explicitly without silently reopening it`() = runBlocking {
        fixture { _, cache, library ->
            val denied = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it) }, validateRead = {
                throw LibraryTextIndexException(LibraryTextIndexFailure.RECOVERY)
            })
            assertFailure(LibraryTextIndexFailure.RECOVERY) { denied.rebuild {} }
            assertFalse(cache.isOpen)
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it) })
            service.rebuild {}; val old = service.observe("injection").first().hits.single(); cache.close()
            assertFailure(LibraryTextIndexFailure.SOURCE) { service.resolve(old) }
            assertFalse(cache.isOpen)
        }
    }

    private suspend fun assertFailure(reason: LibraryTextIndexFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (failure: LibraryTextIndexException) { assertEquals(reason, failure.reason) }
    }

    private val pdf get() = book.copy(id = "pdf", format = BookFormat.PDF, pageCount = 3,
        storageName = "pdf.pdf", sha256 = "f".repeat(64))
    private fun pdfDocument(text: String = "Dependency injection in PDF 🌿") =
        ExtractedBookText(pdf.id, pdf.sha256, listOf(ExtractedTextChunk(2, 0, "", "d".repeat(64), null,
            text, BookFormat.PDF, 2)), 3, sourceFormat = BookFormat.PDF)
    private fun mixedExtractor(extractPdf: suspend () -> ExtractedBookText = { pdfDocument() }) = object : BookTextExtractor {
        override fun supports(book: Book) = true
        override suspend fun extract(book: Book) = if (book.format == BookFormat.PDF) extractPdf() else document(book)
    }

    @Test fun `real FTS mixes EPUB and PDF while question sources retain the exact original PDF page`() = runBlocking {
        fixture { main, cache, library ->
            library.add(pdf)
            val service = LocalLibraryTextIndex(cache, library, mixedExtractor())
            service.rebuild {}
            val result = service.observe("dependency injection").first()
            assertEquals(2, result.totalMatches)
            val hit = result.hits.single { it.book.format == BookFormat.PDF }
            assertEquals(BookFormat.PDF, hit.target.sourceFormat); assertEquals(2, hit.target.pdfPageIndex)
            assertEquals("", hit.target.resourceHref); assertNull(hit.chapterTitle)
            assertEquals(hit, service.resolve(hit))
            assertEquals(listOf(hit), service.observeBook("dependency injection", BookTextScope(pdf.id, pdf.sha256)).first().hits)
            val sources = service.prepare(BookTextScope(pdf.id, pdf.sha256), "injection")
            assertEquals(listOf(hit), sources.references.map { it.hit })
            val request = com.yaprak.reader.domain.assistant.BookQuestionRequest.create(sources, "Bu kavramı açıkla")
            assertEquals(2, request.sources.references.single().hit.target.pdfPageIndex)
            assertNull(request.sources.section); assertNull(request.sources.chapter)
            assertNull(main.reading().resume(pdf.id, pdf.sha256)); assertEquals(pdf, library.findById(pdf.id))
        }
    }

    @Test fun `unsupported native capability rejects existing PDF hits without extracting or touching EPUB data`() = runBlocking {
        fixture { _, cache, library ->
            library.add(pdf)
            val supported = LocalLibraryTextIndex(cache, library, mixedExtractor()); supported.rebuild {}
            val hit = supported.observe("dependency").first().hits.single { it.book.format == BookFormat.PDF }
            val oldDevice = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it) })
            assertEquals(1, oldDevice.observe("dependency").first().totalMatches)
            assertEquals(LibraryTextBookState.UNSUPPORTED, oldDevice.observe("").first().books.single { it.book.id == pdf.id }.state)
            assertNull(oldDevice.resolve(hit))
            oldDevice.rebuild {}
            assertEquals(0, cache.index().books().single { it.bookId == pdf.id }.chunkCount)
            assertEquals(1, oldDevice.observe("dependency").first().totalMatches)
        }
    }

    @Test fun `corrupt typed PDF rows cannot retarget pages or borrow chapter proofs`() = runBlocking {
        fixture { _, cache, library ->
            library.add(pdf); val service = LocalLibraryTextIndex(cache, library, mixedExtractor())
            for ((field, value) in listOf<Pair<String, Any>>("sourceFormat" to "OTHER", "sourceFormat" to "EPUB",
                "pdfPageIndex" to 1.5, "pdfPageIndex" to 3, "readingOrderIndex" to 1, "chapterTitle" to "Fake chapter")) {
                service.rebuild {}; val old = service.observe("dependency").first().hits.single { it.book.id == pdf.id }
                cache.openHelper.writableDatabase.execSQL("UPDATE text_index_chunks SET $field = ? WHERE rowid = ?", arrayOf(value, old.id))
                assertFailure(LibraryTextIndexFailure.SOURCE) { service.resolve(old) }
                assertFailure(LibraryTextIndexFailure.SOURCE) { service.observe("dependency").first() }
            }
            service.rebuild {}; assertEquals(2, service.observe("dependency").first().totalMatches)
        }
    }

    @Test fun `cancelled PDF extraction preserves previous committed generation and no partial matches`() = runBlocking {
        fixture { _, cache, library ->
            library.add(pdf); val entered = CompletableDeferred<Unit>(); var block = false
            val service = LocalLibraryTextIndex(cache, library, mixedExtractor {
                if (block) { entered.complete(Unit); awaitCancellation() } else pdfDocument()
            })
            service.rebuild {}; val old = service.observe("dependency").first().hits.single { it.book.id == pdf.id }
            block = true; val job = launch { service.rebuild {} }; entered.await(); job.cancelAndJoin()
            assertEquals(old, service.resolve(old)); assertEquals(2, service.observe("dependency").first().totalMatches)
        }
    }

    @Test fun `empty PDF and failed whole extraction get explicit states without partial or stale search results`() = runBlocking {
        fixture { main, cache, library ->
            library.add(pdf)
            for (state in listOf(LibraryTextBookState.NO_TEXT, LibraryTextBookState.LIMIT, LibraryTextBookState.FAILED)) {
                LocalLibraryTextIndex(cache, library, mixedExtractor()).rebuild {}
                val service = LocalLibraryTextIndex(cache, library, mixedExtractor {
                    when (state) {
                        LibraryTextBookState.NO_TEXT -> ExtractedBookText(pdf.id, pdf.sha256, emptyList(), 3, sourceFormat = BookFormat.PDF)
                        LibraryTextBookState.LIMIT -> throw TextExtractionException(TextExtractionFailure.LIMIT)
                        else -> throw TextExtractionException(TextExtractionFailure.SOURCE)
                    }
                })
                service.rebuild {}; val result = service.observe("dependency").first()
                assertEquals(1, result.totalMatches); assertEquals(state, result.books.single { it.book.id == pdf.id }.state)
                val ledger = cache.index().books().single { it.bookId == pdf.id }
                assertEquals(0, ledger.chunkCount); assertEquals(0L, ledger.stringBytes)
                assertNull(main.reading().resume(pdf.id, pdf.sha256))
            }
        }
    }
}
