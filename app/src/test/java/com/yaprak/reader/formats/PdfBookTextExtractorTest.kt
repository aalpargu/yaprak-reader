package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.formats.pdf.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PdfBookTextExtractorTest {
    private class Native(val pages: List<List<String>>) : PdfNativeRenderer {
        override val pageCount get() = pages.size
        override var textAvailable = true
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        var onText: (Int) -> Unit = {}
        var onClose: () -> Unit = {}
        override fun openPage(pageIndex: Int) = object : PdfNativePage {
            init { events += "open:$pageIndex" }
            override val width = 600
            override val height = 800
            override fun render(bitmap: Bitmap) = error("Index must not render or OCR")
            override fun textContents(): List<String> { events += "text:$pageIndex"; onText(pageIndex); return pages[pageIndex] }
            override fun close() { events += "page-close:$pageIndex" }
        }
        override fun close() { events += "document-close"; onClose() }
    }
    private suspend fun fixture(native: Native, supported: Boolean = true,
        block: suspend (PdfBookTextExtractor, Book, File) -> Unit) {
        val dir = Files.createTempDirectory("yaprak-pdf-index-").toFile()
        val file = File(dir, "book.pdf").apply { writeText("Private bytes for controlled native seam") }
        val book = Book("pdf", "Source", null, BookFormat.PDF, "book.pdf", "book.pdf", file.length(),
            indexedPdfTextHash(file.readText()), 1, pageCount = native.pageCount)
        val service = PdfBookTextExtractor(VerifiedBookFile(dir), { PdfDocument(native) }, { supported })
        try { block(service, book, file) }
        finally { assertTrue(file.delete()); assertTrue(dir.delete()) }
    }

    @Test fun `complete text pages empty pages and Unicode chunk overlap preserve real page text hashes`() = runBlocking {
        val long = "🌿".repeat(1600) + " dependency injection"
        val native = Native(listOf(listOf("First\n page"), emptyList(), listOf(long)))
        fixture(native) { service, book, _ ->
            val document = service.extract(book)
            assertEquals(BookFormat.PDF, document.sourceFormat); assertEquals(3, document.resourceCount)
            assertEquals(listOf(0, 2), document.chunks.map { it.readingOrderIndex }.distinct())
            assertTrue(document.chunks.all { it.sourceFormat == BookFormat.PDF && it.resourceHref.isEmpty() &&
                it.pdfPageIndex == it.readingOrderIndex && it.chapterTitle == null && it.text.length <= MAX_INDEX_CHUNK_TEXT })
            assertTrue(document.chunks.filter { it.pdfPageIndex == 2 }.all { it.resourceSha256 == indexedPdfTextHash(long) })
            assertEquals(indexedPdfTextHash("First page"), document.chunks.first().resourceSha256)
            assertTrue(document.chunks.all { validPdfText(it.text) })
            assertEquals(listOf("open:0", "text:0", "page-close:0", "open:1", "text:1", "page-close:1",
                "open:2", "text:2", "page-close:2", "document-close"), native.events)
        }
    }

    @Test fun `old API admission and changed private file fail before native opening`() = runBlocking {
        val native = Native(listOf(listOf("text")))
        fixture(native, supported = false) { service, book, _ ->
            assertFalse(service.supports(book))
            assertEquals(TextExtractionFailure.UNSUPPORTED, failure { service.extract(book) })
            assertTrue(native.events.isEmpty())
        }
        fixture(native) { service, book, file ->
            file.writeText("Different bytes")
            assertEquals(TextExtractionFailure.SOURCE, failure { service.extract(book) })
            assertTrue(native.events.isEmpty())
        }
    }

    @Test fun `page count mismatch resource limit and malformed native text never return a partial document`() = runBlocking {
        for (kind in listOf("count", "resources", "native-limit", "malformed")) {
            val native = Native(if (kind == "resources") List(MAX_INDEX_BOOK_RESOURCES + 1) { emptyList() }
                else listOf(listOf("good"), listOf(if (kind == "malformed") "bad\uD800" else "x".repeat(MAX_PDF_PAGE_TEXT))))
            fixture(native) { service, book, _ ->
                val expected = if (kind in listOf("resources", "native-limit")) TextExtractionFailure.LIMIT else TextExtractionFailure.SOURCE
                assertEquals(expected, failure { service.extract(if (kind == "count") book.copy(pageCount = 9) else book) })
                assertEquals(1, native.events.count { it == "document-close" })
                if (kind in listOf("count", "resources")) assertFalse(native.events.any { it.startsWith("open:") })
                else assertEquals(native.events.count { it.startsWith("open:") }, native.events.count { it.startsWith("page-close:") })
            }
        }
    }

    @Test fun `empty native text traverses the entire document without starting OCR`() = runBlocking {
        val native = Native(List(3) { emptyList() })
        fixture(native) { service, book, _ ->
            val document = service.extract(book)
            assertTrue(document.chunks.isEmpty()); assertEquals(3, document.resourceCount)
            assertEquals(3, native.events.count { it.startsWith("text:") }); assertEquals("document-close", native.events.last())
        }
    }

    @Test fun `post close file recheck and native cleanup failure forbid success`() = runBlocking {
        for (cleanupFailure in listOf(false, true)) {
            val native = Native(listOf(listOf("text")))
            fixture(native) { service, book, file ->
                native.onClose = { if (cleanupFailure) throw java.io.IOException("Cleanup failed") else file.writeText("Changed after scan") }
                assertEquals(TextExtractionFailure.SOURCE, failure { service.extract(book) })
                assertEquals(1, native.events.count { it == "document-close" })
            }
        }
    }

    @Test fun `cancellation retains native ownership until cleanup returns without publishing results`() = runBlocking {
        val native = Native(listOf(listOf("text")))
        fixture(native) { service, book, _ ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            native.onClose = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val job = async(Dispatchers.IO) { service.extract(book) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); job.cancel()
            try { assertFalse(job.isCompleted) } finally { release.countDown() }
            assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
            job.join(); assertEquals(1, native.events.count { it == "document-close" })
        }
    }

    @Test fun `whole book payload budget rejects oversized text without partial success`() = runBlocking {
        val native = Native(List(40) { listOf("x".repeat(250_000)) })
        fixture(native) { service, book, _ ->
            assertEquals(TextExtractionFailure.LIMIT, failure { service.extract(book) })
            assertEquals(1, native.events.count { it == "document-close" })
            assertTrue(native.events.count { it.startsWith("text:") } < native.pageCount)
        }
    }

    private suspend fun failure(block: suspend () -> Unit) =
        (runCatching { block() }.exceptionOrNull() as TextExtractionException).reason
}
