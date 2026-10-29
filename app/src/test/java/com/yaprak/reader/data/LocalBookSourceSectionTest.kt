package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.search.*
import com.yaprak.reader.domain.assistant.BookQuestionRequest
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.TestBooks
import com.yaprak.reader.formats.epub.EpubBookTextExtractor
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
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
class LocalBookSourceSectionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val book = Book("section-book", "Kaynak kitap", null, BookFormat.EPUB,
        "section.epub", "section.epub", 12, "a".repeat(64), 1)
    private val scope = BookTextScope(book.id, book.sha256)
    private fun document(book: Book, firstCount: Int = 3) = ExtractedBookText(book.id, book.sha256,
        (0 until firstCount + 2).map { index ->
            val first = index < firstCount
            ExtractedTextChunk(if (first) 0 else 2, index,
                if (first) "EPUB/first.xhtml" else "EPUB/second.xhtml",
                (if (first) "b" else "c").repeat(64), if (first) "Birinci" else "İkinci",
                if (first) "İlk dosya özgün metin 🌿 $index" else "Diğer dosya ayrı metin $index")
        }, 3)
    private suspend fun fixture(block: suspend (LibraryDatabase, TextIndexDatabase, RoomLibraryRepository) -> Unit) {
        val main = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val cache = Room.inMemoryDatabaseBuilder(context, TextIndexDatabase::class.java).build()
        try { val library = RoomLibraryRepository(main.books()); library.add(book); block(main, cache, library) }
        finally { cache.close(); main.close() }
    }
    private suspend fun failure(reason: BookSourceFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (failed: BookSourceException) { assertEquals(reason, failed.reason) }
    }
    @Test fun catalogAndCompleteSelectedFileAreLocalAndDoNotWriteProgress() = runBlocking {
        fixture { main, cache, library ->
            var extracts = 0
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { extracts++; document(it) })
            service.rebuild {}; extracts = 0
            val ledger = cache.index().books()
            val progress = ReadingProgressEntity(book.id, book.sha256, 1, "{}", "EPUB/first.xhtml", 123)
            main.reading().save(progress)
            val catalog = service.listSections(scope)
            assertEquals(0, extracts); assertEquals(listOf(0, 2), catalog.map { it.readingOrderIndex })
            assertEquals(listOf(0, 3), catalog.map { it.firstChunkIndex }); assertEquals(listOf(3, 2), catalog.map { it.chunkCount })
            try { (catalog as MutableList<*>).clear(); fail("Mutable catalog") } catch (_: UnsupportedOperationException) { }
            val prepared = service.prepareSection(scope, catalog.first())
            assertEquals(1, extracts); assertEquals(catalog.first(), prepared.section)
            assertEquals(3, prepared.totalMatches); assertEquals("", prepared.query); assertFalse(prepared.truncated)
            assertEquals(document(book).chunks.take(3).map { it.text }, prepared.references.map { it.hit.text })
            assertTrue(prepared.references.all { it.hit.target.resourceHref == "EPUB/first.xhtml" })
            BookQuestionRequest.create(prepared, "Özetle")
            assertNotEquals(prepared.requestId, service.prepareSection(scope, catalog.first()).requestId)
            assertEquals(ledger, cache.index().books()); assertEquals(progress, main.reading().resume(book.id, book.sha256))
        }
    }
    @Test fun oversizeAndRebuiltGenerationRefusePartialOrRetargetedSources() = runBlocking {
        fixture { _, cache, library ->
            var extracts = 0; var count = 65
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { extracts++; document(it, count) })
            service.rebuild {}; extracts = 0
            val large = service.listSections(scope).first()
            assertEquals(65, large.chunkCount)
            failure(BookSourceFailure.LIMIT) { service.prepareSection(scope, large) }; assertEquals(0, extracts)
            val short = service.listSections(scope).last()
            assertEquals(2, service.prepareSection(scope, short).references.size)
            count = 3; service.rebuild {}
            failure(BookSourceFailure.SOURCE) { service.prepareSection(scope, short) }
            val chosen = service.listSections(scope).first()
            for (wrong in listOf(chosen.copy(chunkCount = 2), chosen.copy(resourceHref = "EPUB/other.xhtml"),
                chosen.copy(resourceSha256 = "d".repeat(64)), chosen.copy(chapterTitle = "Other"),
                chosen.copy(scope = BookTextScope("other", book.sha256)))) {
                val before = extracts
                failure(BookSourceFailure.SOURCE) { service.prepareSection(scope, wrong) }; assertEquals(before, extracts)
            }
        }
    }
    @Test fun corruptRawCatalogMetadataAndSqlTypesRefuseChoices() = runBlocking {
        fixture { _, cache, library ->
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it) })
            for ((field, value) in listOf("chunkIndex" to 7, "readingOrderIndex" to -1,
                "resourceHref" to "EPUB/first.xhtml#one", "resourceSha256" to "BAD",
                "generation" to UUID.randomUUID().toString(), "chapterTitle" to "Mismatched title")) {
                service.rebuild {}
                cache.openHelper.writableDatabase.execSQL(
                    "UPDATE text_index_chunks SET $field = ? WHERE bookId = ? AND chunkIndex = 1", arrayOf<Any>(value, book.id))
                failure(BookSourceFailure.SOURCE) { service.listSections(scope) }
            }
            service.rebuild {}
            cache.openHelper.writableDatabase.execSQL("UPDATE text_index_chunks SET readingOrderIndex = 0.5 WHERE bookId = ?", arrayOf(book.id))
            failure(BookSourceFailure.SOURCE) { service.listSections(scope) }
        }
    }
    @Test fun forgedTextAndCoherentlyMissingChunksAreNotCompleteProof() = runBlocking {
        fixture { _, cache, library ->
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it) })
            service.rebuild {}
            var chosen = service.listSections(scope).first()
            cache.openHelper.writableDatabase.execSQL(
                "UPDATE text_index_chunks SET text = ?, searchText = ? WHERE bookId = ? AND chunkIndex = 1",
                arrayOf<Any>("forged", "forged", book.id))
            failure(BookSourceFailure.SOURCE) { service.prepareSection(scope, chosen) }
            service.rebuild {}
            cache.openHelper.writableDatabase.execSQL("DELETE FROM text_index_chunks WHERE bookId = ? AND chunkIndex = 1", arrayOf(book.id))
            cache.openHelper.writableDatabase.execSQL(
                "UPDATE text_index_chunks SET chunkIndex = chunkIndex - 1 WHERE bookId = ? AND chunkIndex > 1", arrayOf(book.id))
            cache.openHelper.writableDatabase.execSQL("UPDATE text_index_books SET chunkCount = chunkCount - 1 WHERE bookId = ?", arrayOf(book.id))
            chosen = service.listSections(scope).first(); assertEquals(2, chosen.chunkCount)
            failure(BookSourceFailure.SOURCE) { service.prepareSection(scope, chosen) }
        }
    }
    @Test fun missingUnsupportedRecoveryAndChangedMetadataFailWithoutSourceWrites() = runBlocking {
        fixture { main, cache, library ->
            var change = false
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor {
                if (change) library.updateMetadata(it.id, it.sha256, "Changed", null)
                document(it)
            })
            failure(BookSourceFailure.NOT_INDEXED) { service.listSections(scope) }
            service.rebuild {}
            val chosen = service.listSections(scope).first(); val ledger = cache.index().books()
            change = true; failure(BookSourceFailure.SOURCE) { service.prepareSection(scope, chosen) }
            assertEquals(ledger, cache.index().books()); assertNull(main.reading().resume(book.id, book.sha256))
            val denied = LocalLibraryTextIndex(cache, library, BookTextExtractor { error("Recovery opened source") },
                validateRead = { throw LibraryTextIndexException(LibraryTextIndexFailure.RECOVERY) })
            failure(BookSourceFailure.RECOVERY) { denied.listSections(scope) }
            failure(BookSourceFailure.RECOVERY) { denied.prepareSection(scope, chosen) }
            val unsupported = LocalLibraryTextIndex(cache, library, object : BookTextExtractor {
                override fun supports(book: Book) = false
                override suspend fun extract(book: Book): ExtractedBookText = error("Unsupported source opened")
            })
            failure(BookSourceFailure.UNSUPPORTED) { unsupported.listSections(scope) }
            main.books().deleteExact(book.id, book.sha256)
            failure(BookSourceFailure.SOURCE) { service.prepareSection(scope, chosen) }
        }
    }
    @Test fun queuedAndCancellationIgnoringExtractionCannotPublishLateSources() = runBlocking {
        fixture { _, cache, library ->
            val mutex = Mutex()
            var late = false; var extracts = 0
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor {
                extracts++; assertFalse(mutex.tryLock())
                if (late) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                document(it)
            }, mutex)
            service.rebuild {}
            val chosen = service.listSections(scope).first(); val ledger = cache.index().books()
            mutex.lock()
            val before = extracts
            val queued = launch(Dispatchers.Default) { service.prepareSection(scope, chosen) }
            yield(); queued.cancelAndJoin(); mutex.unlock(); assertEquals(before, extracts)
            late = true; var returned = false
            val operation = launch(Dispatchers.Default) { service.prepareSection(scope, chosen); returned = true }
            withTimeout(5000) { entered.await() }; operation.cancel(); release.complete(Unit)
            withTimeout(5000) { operation.join() }
            assertTrue(operation.isCancelled); assertFalse(returned); assertEquals(ledger, cache.index().books())
        }
    }
    @Test fun productionEpubResourceReopensFileAndRefusesSameSizeReplacement() = runBlocking {
        fixture { main, cache, library ->
            val directory = File(context.filesDir, "books").apply { mkdirs() }
            val file = TestBooks.epub(File(directory, "complete-" + UUID.randomUUID() + ".epub"))
            try {
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                val actual = book.copy(id = "actual-section", storageName = file.name, byteSize = file.length(), sha256 = hash)
                library.add(actual)
                val actualScope = BookTextScope(actual.id, actual.sha256)
                val service = LocalLibraryTextIndex(cache, library, EpubBookTextExtractor(context))
                service.rebuild {}
                val catalog = service.listSections(actualScope)
                assertEquals(1, catalog.size); assertEquals("EPUB/chapter.xhtml", catalog.single().resourceHref)
                val evidence = service.prepareSection(actualScope, catalog.single())
                assertEquals(catalog.single().chunkCount, evidence.references.size)
                assertTrue(evidence.references.single().hit.text.contains("Güneş doğdu."))
                BookQuestionRequest.create(evidence, "Özetle"); assertNull(main.reading().resume(actual.id, actual.sha256))
                val length = file.length()
                RandomAccessFile(file, "rw").use { stored -> val first = stored.readByte(); stored.seek(0); stored.writeByte(first.toInt() xor 1) }
                assertEquals(length, file.length())
                failure(BookSourceFailure.SOURCE) { service.prepareSection(actualScope, catalog.single()) }
            } finally { assertTrue(file.delete()) }
        }
    }

    @Test fun realLongResourceAndOversizeRefusalUseCompleteOriginalFilesNotTocFragments() = runBlocking {
        fixture { main, cache, library ->
            val directory = File(context.filesDir, "books").apply { mkdirs() }
            for (length in listOf(111_000, 115_000)) {
                val file = com.yaprak.reader.fixtures.TestLargeSourceBooks.epub(
                    File(directory, "large-" + UUID.randomUUID() + ".epub"), length)
                try {
                    val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                    val actual = book.copy(id = "actual-long-" + length, storageName = file.name, byteSize = file.length(), sha256 = hash)
                    library.add(actual)
                    val actualScope = BookTextScope(actual.id, actual.sha256)
                    val service = LocalLibraryTextIndex(cache, library, EpubBookTextExtractor(context))
                    service.rebuild {}
                    val sections = service.listSections(actualScope)
                    assertEquals(2, sections.size)
                    assertEquals(listOf("EPUB/chapter0.xhtml", "EPUB/chapter1.xhtml"), sections.map { it.resourceHref })
                    val ledger = cache.index().books()
                    if (length == 111_000) {
                        assertEquals(64, sections.first().chunkCount)
                        val prepared = service.prepareSection(actualScope, sections.first())
                        assertEquals(64, prepared.references.size); assertEquals("S64", prepared.references.last().label)
                        assertTrue(prepared.references.last().hit.text.contains("LAST FIRST DOCUMENT MARKER"))
                        assertTrue(prepared.references.none { it.hit.text.contains("SEPARATE RESOURCE") })
                        BookQuestionRequest.create(prepared, "Özetle")
                    } else {
                        assertTrue(sections.first().chunkCount > 64)
                        failure(BookSourceFailure.LIMIT) { service.prepareSection(actualScope, sections.first()) }
                    }
                    assertEquals(ledger, cache.index().books()); assertNull(main.reading().resume(actual.id, actual.sha256))
                } finally { assertTrue(file.delete()) }
            }
        }
    }
    @Test fun corruptionOfTheSixtyFourthCachedChunkCannotBecomeCompleteEvidence() = runBlocking {
        fixture { main, cache, library ->
            val service = LocalLibraryTextIndex(cache, library, BookTextExtractor { document(it, 64) })
            service.rebuild {}
            val section = service.listSections(scope).first()
            assertEquals(64, section.chunkCount)
            val complete = service.prepareSection(scope, section)
            assertEquals((0 until 64).toList(), complete.references.map { it.hit.chunkIndex })
            assertEquals("S64", complete.references.last().label)
            cache.openHelper.writableDatabase.execSQL(
                "UPDATE text_index_chunks SET text = ?, searchText = ? WHERE bookId = ? AND chunkIndex = 63",
                arrayOf<Any>("forged final piece", "forged final piece", book.id))
            failure(BookSourceFailure.SOURCE) { service.prepareSection(scope, section) }
            assertNull(main.reading().resume(book.id, book.sha256))
        }
    }

}

