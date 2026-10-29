package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.ReadingProgressEntity
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.data.search.LocalLibraryTextIndex
import com.yaprak.reader.data.search.TextIndexDatabase
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.TestBooks
import com.yaprak.reader.formats.epub.EpubBookTextExtractor
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LocalBookSourceRetrieverTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val book = Book("z", "Kaynak kitap", "Deniz", BookFormat.EPUB, "z.epub", "z.epub", 12, "a".repeat(64), 1)
    private val scope = BookTextScope(book.id, book.sha256)
    private fun document(book: Book, count: Int = 1, text: String = "Özgün 🌿 dependency injection") =
        ExtractedBookText(book.id, book.sha256, (0 until count).map {
            ExtractedTextChunk(0, it, "OPS/chapter.xhtml", "b".repeat(64), "Kavramlar", "$text $it")
        }, 1)
    private suspend fun fixture(block: suspend (LibraryDatabase, TextIndexDatabase, RoomLibraryRepository) -> Unit) {
        val main = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val cache = Room.inMemoryDatabaseBuilder(context, TextIndexDatabase::class.java).build()
        try { val library = RoomLibraryRepository(main.books()); library.add(book); block(main, cache, library) }
        finally { cache.close(); main.close() }
    }

    @Test fun `book scope precedes real FTS result cap and prepared evidence is bounded exact immutable and local`() = runBlocking {
        fixture { main, cache, library ->
            val other = book.copy(id = "a", sha256 = "c".repeat(64), storageName = "a.epub")
            library.add(other)
            val extracts = mutableListOf<String>()
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor {
                extracts += it.id; document(it, if (it.id == "a") 220 else 12)
            })
            val progress = ReadingProgressEntity(book.id, book.sha256, 1, "{}", "OPS/chapter.xhtml", 123)
            main.reading().save(progress)
            service.rebuild {}; extracts.clear()
            assertTrue(service.observe("dependency injection").first().hits.all { it.book.id == "a" })
            val scoped = service.observeBook("dependency injection", scope).first()
            assertEquals(scope, scoped.scope); assertEquals(12, scoped.totalMatches)
            assertEquals(12, scoped.hits.size); assertTrue(scoped.hits.all { it.book.id == book.id })
            assertTrue(extracts.isEmpty())
            val generations = cache.index().books()
            val prepared = service.prepare(scope, "dependency injection")
            assertEquals(listOf(book.id), extracts)
            assertEquals(12, prepared.totalMatches); assertEquals(8, prepared.references.size); assertTrue(prepared.truncated)
            assertEquals((1..8).map { "S$it" }, prepared.references.map { it.label })
            assertEquals(scoped.hits.take(8), prepared.references.map { it.hit })
            assertEquals((0..7).toList(), prepared.references.map { it.hit.chunkIndex })
            assertTrue(prepared.references.all { it.hit.readingOrderIndex == 0 && it.hit.text.contains("Özgün 🌿") })
            assertEquals(prepared.requestId, UUID.fromString(prepared.requestId).toString())
            try { (prepared.references as MutableList<*>).clear(); fail("Mutable evidence") }
            catch (_: UnsupportedOperationException) { }
            assertNotEquals(prepared.requestId, service.prepare(scope, "dependency injection").requestId)
            assertEquals(generations, cache.index().books())
            assertEquals(progress, main.reading().resume(book.id, book.sha256))
            assertEquals(2, main.books().observeAll().first().size)
        }
    }

    @Test fun `invalid missing deleted and changed scope never falls back to other books or opens sources`() = runBlocking {
        fixture { main, cache, library ->
            var extracts = 0
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { extracts++; document(it) })
            service.rebuild {}; extracts = 0
            for (invalid in listOf(BookTextScope("", book.sha256), BookTextScope("bad\u0000id", book.sha256),
                BookTextScope("x".repeat(201), book.sha256), BookTextScope("bad\uD800", book.sha256),
                BookTextScope(book.id, "A".repeat(64)), BookTextScope("missing", book.sha256),
                BookTextScope(book.id, "d".repeat(64)))) {
                failure(BookSourceFailure.SOURCE) { service.prepare(invalid, "dependency") }
                try { service.observeBook("dependency", invalid).first(); fail("Scope fallback") }
                catch (failed: LibraryTextIndexException) { assertEquals(LibraryTextIndexFailure.SOURCE, failed.reason) }
            }
            main.books().deleteExact(book.id, book.sha256)
            failure(BookSourceFailure.SOURCE) { service.prepare(scope, "dependency") }
            assertEquals(0, extracts)
        }
    }

    @Test fun `cached text title source and order cannot be passed as real evidence even with unchanged book hash`() = runBlocking {
        fixture { _, cache, library ->
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it) })
            for ((field, value) in listOf("text" to "dependency fabricated answer", "chapterTitle" to "Fabricated chapter",
                "resourceHref" to "OPS/other.xhtml", "resourceSha256" to "f".repeat(64), "readingOrderIndex" to 1)) {
                service.rebuild {}
                val hit = service.observeBook("dependency", scope).first().hits.single()
                cache.openHelper.writableDatabase.execSQL("UPDATE text_index_chunks SET $field = ? WHERE rowid = ?", arrayOf<Any>(value, hit.id))
                if (field == "text") cache.openHelper.writableDatabase.execSQL(
                    "UPDATE text_index_chunks SET searchText = ? WHERE rowid = ?", arrayOf<Any>(libraryTextSearchKey(value as String), hit.id))
                failure(BookSourceFailure.SOURCE) { service.prepare(scope, "dependency") }
            }
        }
    }

    @Test fun `empty matching set proves source while absent index unsupported and invalid query fail explicitly`() = runBlocking {
        fixture { _, cache, library ->
            var extracts = 0
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { extracts++; document(it) })
            failure(BookSourceFailure.NOT_INDEXED) { service.prepare(scope, "dependency") }
            assertEquals(0, extracts)
            service.rebuild {}; extracts = 0
            for (invalid in listOf("", "   ", "*", "x".repeat(201), (1..17).joinToString(" ") { "x$it" }, "bad\u0000"))
                failure(BookSourceFailure.INVALID_QUERY) { service.prepare(scope, invalid) }
            assertEquals(0, extracts)
            val empty = service.prepare(scope, "unmatched")
            assertTrue(empty.references.isEmpty()); assertEquals(0, empty.totalMatches); assertFalse(empty.truncated)
            assertEquals(1, extracts)
            val pdf = book.copy(id = "pdf", format = BookFormat.PDF, sha256 = "c".repeat(64))
            library.add(pdf)
            failure(BookSourceFailure.UNSUPPORTED) { service.prepare(BookTextScope(pdf.id, pdf.sha256), "dependency") }
            assertEquals(1, extracts)
        }
    }

    @Test fun `fresh extraction failures do not publish or repair cache and metadata changed during proof rejects snapshot`() = runBlocking {
        fixture { _, cache, library ->
            var reason: TextExtractionFailure? = null; var changeMetadata = false
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor {
                reason?.let { failure -> throw TextExtractionException(failure) }
                if (changeMetadata) library.updateMetadata(it.id, it.sha256, "Changed during proof", null)
                document(it)
            })
            service.rebuild {}; val ledger = cache.index().books()
            for ((error, expected) in listOf(TextExtractionFailure.UNSUPPORTED to BookSourceFailure.UNSUPPORTED,
                TextExtractionFailure.LIMIT to BookSourceFailure.LIMIT, TextExtractionFailure.INVALID to BookSourceFailure.SOURCE,
                TextExtractionFailure.SOURCE to BookSourceFailure.SOURCE)) {
                reason = error; failure(expected) { service.prepare(scope, "dependency") }
                assertEquals(ledger, cache.index().books())
            }
            reason = null; changeMetadata = true
            failure(BookSourceFailure.SOURCE) { service.prepare(scope, "dependency") }
            assertEquals(ledger, cache.index().books())
        }
    }

    @Test fun `cancelled source proof retains cancellation and cannot return late evidence or touch the cache`() = runBlocking {
        fixture { _, cache, library ->
            var mode = 0
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val cancellation = CancellationException("controlled source cancellation")
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor {
                if (mode == 1) throw cancellation
                if (mode == 2) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                document(it)
            })
            service.rebuild {}; val ledger = cache.index().books()
            mode = 1
            try { service.prepare(scope, "dependency"); fail("Cancellation classified as failure") }
            catch (cancelled: CancellationException) {
                assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.any { it === cancellation })
                assertTrue(currentCoroutineContext().isActive)
            }
            mode = 2; var returned = false
            val job = launch(Dispatchers.Default) { service.prepare(scope, "dependency"); returned = true }
            withTimeout(5000) { entered.await() }; job.cancel(); release.complete(Unit)
            withTimeout(5000) { job.join() }
            assertTrue(job.isCancelled); assertFalse(returned); assertEquals(ledger, cache.index().books())
        }
    }

    @Test fun `recovery and queued cancellation stop before source while actual proof holds shared file mutex`() = runBlocking {
        fixture { _, cache, library ->
            val mutex = Mutex(locked = true); val guards = AtomicInteger(); var extracts = 0
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor {
                extracts++; assertFalse(mutex.tryLock()); document(it)
            }, mutex, { guards.incrementAndGet() })
            val queued = launch(Dispatchers.Default) { service.prepare(scope, "dependency") }
            yield(); queued.cancelAndJoin(); mutex.unlock()
            assertEquals(0, guards.get()); assertEquals(0, extracts)
            service.rebuild {}; assertEquals(1, service.prepare(scope, "dependency").references.size)
            val denied = LocalLibraryTextIndex(cache, library, BookTextExtractor { error("Recovery opened source") },
                validateRead = { throw LibraryTextIndexException(LibraryTextIndexFailure.RECOVERY) })
            failure(BookSourceFailure.RECOVERY) { denied.prepare(scope, "dependency") }
        }
    }

    @Test fun `production retriever reopens original EPUB and rejects actual same size file changes before evidence`() = runBlocking {
        fixture { main, cache, library ->
            val directory = File(context.filesDir, "books").apply { mkdirs() }
            val file = TestBooks.epub(File(directory, "sources-${UUID.randomUUID()}.epub"))
            try {
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                val actual = book.copy(id = "actual", storageName = file.name, byteSize = file.length(), sha256 = hash)
                library.add(actual)
                val service = LocalLibraryTextIndex(cache, library, EpubBookTextExtractor(context))
                service.rebuild {}
                val actualScope = BookTextScope(actual.id, actual.sha256)
                val evidence = service.prepare(actualScope, "Güneş")
                assertEquals(1, evidence.references.size)
                assertTrue(evidence.references.single().hit.text.contains("Güneş doğdu."))
                assertEquals("EPUB/chapter.xhtml", evidence.references.single().hit.target.resourceHref)
                assertNull(main.reading().resume(actual.id, actual.sha256))
                val length = file.length()
                RandomAccessFile(file, "rw").use { stored -> val first = stored.readByte(); stored.seek(0); stored.writeByte(first.toInt() xor 1) }
                assertEquals(length, file.length())
                failure(BookSourceFailure.SOURCE) { service.prepare(actualScope, "Güneş") }
                assertEquals(1, service.observeBook("Güneş", actualScope).first().hits.size)
            } finally { file.delete() }
        }
    }

    private suspend fun failure(expected: BookSourceFailure, action: suspend () -> Unit) {
        try { action(); fail("Expected $expected") }
        catch (failed: BookSourceException) { assertEquals(expected, failed.reason) }
    }
}
