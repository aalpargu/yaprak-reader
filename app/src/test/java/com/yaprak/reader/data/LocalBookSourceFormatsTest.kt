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
class LocalBookSourceFormatsTest {
    private class Fixture(val main: LibraryDatabase, val cache: TextIndexDatabase, val directory: File) {
        val library = RoomLibraryRepository(main.books())
        val nativeEvents = mutableListOf<String>()
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
                        return if (textless || pageIndex == 1) emptyList() else listOf("dependency injection original page ${pageIndex + 1}")
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
        val service get() = LocalLibraryTextIndex(cache, library, extractor)
        suspend fun add(format: BookFormat): Book {
            val file = File(directory, "${UUID.randomUUID()}.${format.extension}")
            file.writeText(if (format == BookFormat.TXT) " ".repeat(TXT_PART_CHARACTERS) + "dependency injection original second part 🌿"
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
    @Test fun exactPdfPagesAndOriginalTxtPartsPrepareLocallyWithoutChangingCacheProgressOrCompletion() = runBlocking {
        fixture {
            val pdf = add(BookFormat.PDF); val txt = add(BookFormat.TXT)
            service.rebuild {}; val ledgers = cache.index().books(); nativeEvents.clear()
            for (book in listOf(pdf, txt)) {
                val progress = ReadingProgressEntity(book.id, book.sha256, 1, "untouched saved location", "original", 123)
                main.reading().save(progress)
                val sources = service.prepare(BookTextScope(book.id, book.sha256), "dependency injection")
                BookQuestionRequest.create(sources, "Bu kavramı açıkla")
                assertTrue(sources.references.all { it.hit.book.id == book.id && it.hit.target.sourceFormat == book.format })
                if (book.format == BookFormat.PDF) {
                    assertEquals(listOf(0, 2), sources.references.map { it.hit.target.pdfPageIndex })
                    assertTrue(sources.references.all { it.hit.chapterTitle == null && it.hit.target.resourceHref.isEmpty() })
                } else {
                    val part = StrictTxt.read(File(directory, book.storageName)).last()
                    assertEquals(listOf(part.href), sources.references.map { it.hit.target.resourceHref })
                    assertEquals(part.sha256, sources.references.single().hit.target.resourceSha256)
                    assertEquals("Metin 2", sources.references.single().hit.chapterTitle)
                }
                assertEquals(progress, main.reading().resume(book.id, book.sha256))
                assertTrue(main.reading().observed(book.id, book.sha256).isEmpty())
                assertEquals(book.organization, library.findById(book.id)!!.organization)
            }
            assertEquals(ledgers, cache.index().books())
            assertEquals(listOf("document-open", "text:0", "page-close:0", "text:1", "page-close:1", "text:2", "page-close:2", "document-close"), nativeEvents)
        }
    }
    @Test fun matchingAndEmptyCachedResultsBothRequireCurrentOriginalBytes() = runBlocking {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT)) for (query in listOf("dependency", "unmatched")) fixture {
            val book = add(format); service.rebuild {}
            File(directory, book.storageName).writeText("Changed original bytes")
            failure(BookSourceFailure.SOURCE) { service.prepare(BookTextScope(book.id, book.sha256), query) }
        }
    }
    @Test fun corruptedNativePageOrCanonicalPartCannotBorrowTheSameFileHash() = runBlocking {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT)) fixture {
            val book = add(format); val scope = BookTextScope(book.id, book.sha256)
            for ((column, value) in if (format == BookFormat.PDF) listOf("pdfPageIndex" to 1, "chapterTitle" to "forged", "resourceSha256" to "f".repeat(64))
                else listOf("resourceHref" to "Text/part0001.xhtml", "readingOrderIndex" to 0, "chapterTitle" to "forged")) {
                service.rebuild {}
                val hit = service.observeBook("dependency", scope).first().hits.first()
                cache.openHelper.writableDatabase.execSQL("UPDATE text_index_chunks SET $column = ? WHERE rowid = ?", arrayOf<Any>(value, hit.id))
                failure(BookSourceFailure.SOURCE) { service.prepare(scope, "dependency") }
            }
        }
    }
    @Test fun absentNativeTextCapabilityAndTextlessPdfDoNotStartOcrOrProduceQuestionSources() = runBlocking {
        fixture {
            val book = add(BookFormat.PDF); val scope = BookTextScope(book.id, book.sha256)
            service.rebuild {}; nativeEvents.clear(); nativeSupported = false
            failure(BookSourceFailure.UNSUPPORTED) { service.prepare(scope, "dependency") }; assertTrue(nativeEvents.isEmpty())
            nativeSupported = true; textless = true; service.rebuild {}; nativeEvents.clear()
            assertEquals(LibraryTextBookState.NO_TEXT, service.observeBook("", scope).first().books.single().state)
            failure(BookSourceFailure.UNSUPPORTED) { service.prepare(scope, "dependency") }; assertTrue(nativeEvents.isEmpty())
        }
    }
    @Test fun cancelledProofRetainsThePreviousGenerationAndAllowsAnExplicitFreshRetry() = runBlocking {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT)) fixture {
            val book = add(format); val scope = BookTextScope(book.id, book.sha256); service.rebuild {}
            val before = cache.index().books(); afterExtract = { throw CancellationException("Cancelled source proof") }
            try { service.prepare(scope, "dependency"); fail("Cancellation swallowed") } catch (_: CancellationException) { }
            assertEquals(before, cache.index().books())
            afterExtract = {}; assertTrue(service.prepare(scope, "dependency").references.isNotEmpty())
            assertEquals(before, cache.index().books())
            if (format == BookFormat.PDF) assertEquals(nativeEvents.count { it == "document-open" }, nativeEvents.count { it == "document-close" })
        }
    }
    private suspend fun failure(expected: BookSourceFailure, block: suspend () -> Unit) {
        try { block(); fail("Invalid source accepted") } catch (failure: BookSourceException) { assertEquals(expected, failure.reason) }
    }
}
