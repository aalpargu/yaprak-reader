package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.search.*
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.assistant.BookQuestionRequest
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.formats.txt.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real API34 Room/FTS and original bytes/adapters. Native PDF text capability is an injected seam;
 * the existing API35 extractor tests remain unchanged and physical decoding is not asserted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalCompleteFormatSourceTest {
    private class Fixture(val main: LibraryDatabase, val cache: TextIndexDatabase, val directory: File) {
        val library = RoomLibraryRepository(main.books())
        val nativeEvents = mutableListOf<String>()
        val mutex = kotlinx.coroutines.sync.Mutex()
        var readAllowed = true
        var nativeSupported = true
        var textless = false
        var afterExtract: suspend () -> Unit = {}
        private val files = VerifiedBookFile(directory)
        private val txt = TxtBookTextExtractor(files)
        private val pdf = PdfBookTextExtractor(files, {
            nativeEvents += "document-open"
            PdfDocument(object : PdfNativeRenderer {
                override val pageCount = 3
                override val textAvailable = true
                override fun openPage(pageIndex: Int) = object : PdfNativePage {
                    override val width = 80; override val height = 120
                    override fun render(bitmap: Bitmap) = error("Question sources must not render or OCR")
                    override fun textContents(): List<String> {
                        nativeEvents += "text:$pageIndex"
                        return if (textless || pageIndex == 1) emptyList() else listOf(if (pageIndex == 0) "dependency injection original page one 🌿 ".repeat(700) else "dependency injection original page three")
                    }
                    override fun close() { nativeEvents += "page-close:$pageIndex" }
                }
                override fun close() { nativeEvents += "document-close" }
            })
        }, { nativeSupported })
        val extractor = object : BookTextExtractor {
            override fun supports(book: Book) = when (book.format) {
                BookFormat.PDF -> pdf.supports(book); BookFormat.TXT -> txt.supports(book); else -> false
            }
            override suspend fun extract(book: Book): ExtractedBookText {
                val result = when (book.format) {
                    BookFormat.PDF -> pdf.extract(book); BookFormat.TXT -> txt.extract(book)
                    else -> throw TextExtractionException(TextExtractionFailure.UNSUPPORTED)
                }
                afterExtract(); return result
            }
        }
        val service get() = LocalLibraryTextIndex(cache, library, extractor, fileMutex = mutex, validateRead = { if (!readAllowed) throw LibraryTextIndexException(LibraryTextIndexFailure.RECOVERY) })
        suspend fun add(format: BookFormat): Book {
            val file = File(directory, "${UUID.randomUUID()}.${format.extension}")
            file.writeText(if (format == BookFormat.TXT) "dependency injection original text first part. ".repeat(2000).take(TXT_PART_CHARACTERS) + "dependency injection original second part 🌿"
                else "Private controlled PDF source bytes")
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            return Book(UUID.randomUUID().toString(), "Source", null, format, file.name, file.name, file.length(), hash, 1,
                pageCount = if (format == BookFormat.PDF) 3 else null).also { library.add(it) }
        }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val main = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val cache = Room.inMemoryDatabaseBuilder(context, TextIndexDatabase::class.java).build()
        val directory = Files.createTempDirectory("yaprak-source-formats-").toFile()
        try { Fixture(main, cache, directory).block() }
        finally { cache.close(); main.close(); directory.listFiles().orEmpty().forEach { it.delete() }; directory.delete() }
    }
    @Test fun realRoomCatalogAndCompleteOriginalPageOrPartExceedKeyword8WithoutChangingReadingOrIndex() = runBlocking {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT)) fixture {
            val book = add(format); val scope = BookTextScope(book.id, book.sha256); service.rebuild {}
            val before = cache.index().books(); nativeEvents.clear()
            val catalog = service.listSections(scope)
            assertEquals(if (format == BookFormat.PDF) listOf(0, 2) else listOf(0, 1), catalog.map { it.readingOrderIndex })
            assertTrue(catalog.all { it.sourceFormat == format }); assertTrue(nativeEvents.isEmpty())
            val progress = ReadingProgressEntity(book.id, book.sha256, 1, "untouched", "original", 123)
            main.reading().save(progress)
            val keyword = service.prepare(scope, "dependency injection"); assertEquals(8, keyword.references.size)
            val selected = catalog.first(); val full = service.prepareSection(scope, selected)
            assertTrue(full.references.size > 8); assertEquals(selected.chunkCount, full.references.size)
            assertFalse(full.truncated); assertEquals("", full.query); assertEquals(selected, full.section)
            val extracted = extractor.extract(book).chunks.filter { it.readingOrderIndex == selected.readingOrderIndex }
            assertEquals(extracted.map { it.text }, full.references.map { it.text })
            assertEquals(extracted.map { it.resourceSha256 }, full.references.map { it.hit.target.resourceSha256 })
            assertTrue(full.references.all { it.hit.target.sourceFormat == format && it.hit.target.pdfPageIndex == selected.pdfPageIndex })
            BookQuestionRequest.create(full, "Özetle")
            val last = service.prepareSection(scope, catalog.last())
            assertEquals(catalog.last().readingOrderIndex, last.references.single().hit.readingOrderIndex)
            assertEquals(before, cache.index().books()); assertEquals(progress, main.reading().resume(book.id, book.sha256))
            assertTrue(main.reading().observed(book.id, book.sha256).isEmpty())
            assertEquals(book.organization, library.findById(book.id)!!.organization)
            assertEquals(nativeEvents.count { it == "document-open" }, nativeEvents.count { it == "document-close" })
        }
    }
    @Test fun staleGenerationWrongKindAndChangedOriginalFileNeverBecomeTheSelectedCompleteSource() = runBlocking {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT)) fixture {
            val book = add(format); val scope = BookTextScope(book.id, book.sha256); service.rebuild {}
            val old = service.listSections(scope).first(); service.rebuild {}; nativeEvents.clear()
            failure(BookSourceFailure.SOURCE) { service.prepareSection(scope, old) }; assertTrue(nativeEvents.isEmpty())
            val current = service.listSections(scope).first()
            failure(BookSourceFailure.SOURCE) { service.prepareSection(scope, current.copy(sourceFormat = BookFormat.EPUB)) }
            val file = File(directory, book.storageName); val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            failure(BookSourceFailure.SOURCE) { service.prepareSection(scope, current) }
            assertTrue(nativeEvents.isEmpty())
        }
    }
    @Test fun corruptTypedCacheHeadersAndChangedMetadataAreRefusedWithoutRebuildingOrPartialSources() = runBlocking {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT)) for (mutation in listOf(
            "sourceFormat = 'EPUB'", "sourceFormat = 7", "pdfPageIndex = 99", "chapterTitle = 'invented chapter'", "chunkIndex = 9999")) fixture {
            val book = add(format); val scope = BookTextScope(book.id, book.sha256); service.rebuild {}
            val before = cache.index().books()
            cache.openHelper.writableDatabase.execSQL("UPDATE text_index_chunks SET $mutation WHERE bookId = ? AND chunkIndex = 0", arrayOf(book.id))
            failure(BookSourceFailure.SOURCE) { service.listSections(scope) }; assertEquals(before, cache.index().books())
        }
        for (format in listOf(BookFormat.PDF, BookFormat.TXT)) fixture {
            val book = add(format); val scope = BookTextScope(book.id, book.sha256); service.rebuild {}
            val selected = service.listSections(scope).first(); val before = cache.index().books()
            afterExtract = { assertTrue(library.updateMetadata(book.id, book.sha256, "Current changed title", book.authors)) }
            failure(BookSourceFailure.SOURCE) { service.prepareSection(scope, selected) }
            assertEquals(before, cache.index().books())
        }
    }
    @Test fun unavailableNativeTextAndTextlessPdfFailWithoutOcrAndNoNonEpubTocChapterIsInvented() = runBlocking {
        fixture {
            val book = add(BookFormat.PDF); val scope = BookTextScope(book.id, book.sha256); service.rebuild {}
            val selected = service.listSections(scope).first(); nativeEvents.clear(); nativeSupported = false
            failure(BookSourceFailure.UNSUPPORTED) { service.listSections(scope) }
            failure(BookSourceFailure.UNSUPPORTED) { service.prepareSection(scope, selected) }; assertTrue(nativeEvents.isEmpty())
            nativeSupported = true; textless = true; service.rebuild {}; nativeEvents.clear()
            failure(BookSourceFailure.UNSUPPORTED) { service.listSections(scope) }; assertTrue(nativeEvents.isEmpty())
        }
        for (format in listOf(BookFormat.PDF, BookFormat.TXT)) fixture {
            val book = add(format); val scope = BookTextScope(book.id, book.sha256); service.rebuild {}; nativeEvents.clear()
            failure(BookSourceFailure.UNSUPPORTED) { service.listChapters(scope) }; assertTrue(nativeEvents.isEmpty())
        }
    }
    @Test fun queuedRecoveryAndRealSourceCancellationPreserveCacheAndAllowOnlyExplicitRetry() = runBlocking {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT)) fixture {
            val book = add(format); val scope = BookTextScope(book.id, book.sha256); service.rebuild {}
            val selected = service.listSections(scope).first(); val before = cache.index().books(); nativeEvents.clear()
            mutex.lock(); val entered = CompletableDeferred<Unit>(); var returned = false
            val queued = launch(Dispatchers.Default) { entered.complete(Unit); service.prepareSection(scope, selected); returned = true }
            entered.await(); queued.cancelAndJoin(); mutex.unlock()
            assertFalse(returned); assertTrue(nativeEvents.isEmpty()); assertEquals(before, cache.index().books())
            readAllowed = false
            failure(BookSourceFailure.RECOVERY) { service.prepareSection(scope, selected) }; assertTrue(nativeEvents.isEmpty())
            readAllowed = true; afterExtract = { throw CancellationException("Caller cancelled proof") }
            assertTrue(runCatching { service.prepareSection(scope, selected) }.exceptionOrNull() is CancellationException)
            assertEquals(before, cache.index().books()); afterExtract = {}
            assertEquals(selected.chunkCount, service.prepareSection(scope, selected).references.size)
            assertEquals(before, cache.index().books())
            assertEquals(nativeEvents.count { it == "document-open" }, nativeEvents.count { it == "document-close" })
        }
    }
    @Test fun actualCompletePageAndPartWireOnlyContainQuestionAndExactDetachedSourceText() = runBlocking {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT)) fixture {
            val book = add(format); val scope = BookTextScope(book.id, book.sha256); service.rebuild {}
            val full = service.prepareSection(scope, service.listSections(scope).first())
            val request = BookQuestionRequest.create(full, "Özetle")
            val bytes = com.yaprak.reader.data.assistant.bookQuestionBytes(request)
            val json = org.json.JSONObject(String(bytes, Charsets.UTF_8))
            assertEquals(setOf("request_id", "question", "sources"), json.keys().asSequence().toSet())
            assertEquals(full.requestId, json.getString("request_id")); assertEquals("Özetle", json.getString("question"))
            val rows = json.getJSONArray("sources"); assertEquals(full.references.size, rows.length()); assertTrue(rows.length() > 8)
            for (index in full.references.indices) {
                val row = rows.getJSONObject(index)
                assertEquals(setOf("id", "text"), row.keys().asSequence().toSet())
                assertEquals(full.references[index].label, row.getString("id")); assertEquals(full.references[index].text, row.getString("text"))
            }
            assertTrue(bytes.size <= com.yaprak.reader.domain.assistant.MAX_COMPLETE_BOOK_REQUEST_BYTES)
        }
    }
    private suspend fun failure(expected: BookSourceFailure, block: suspend () -> Unit) {
        try { block(); fail("Invalid complete source accepted") }
        catch (failure: BookSourceException) { assertEquals(expected, failure.reason) }
    }
}
