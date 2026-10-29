package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.fixtures.PdfSelectedAssistantFixtures as P
import com.yaprak.reader.fixtures.SelectedAssistantFixtures as E
import com.yaprak.reader.reader.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real private-file hash and PdfDocument lease; controlled native selection, no decoder acceptance claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfSelectedTextSourcesTest {
    private class Native(val events: MutableList<String>, val text: String) : PdfNativeRenderer {
        override var pageCount = 3; override var textAvailable = true
        var selected: PdfSelectedText? = PdfSelectedText(2, text, P.anchor)
        var onSelect: () -> Unit = {}; var onClose: () -> Unit = {}
        override fun openPage(pageIndex: Int): PdfNativePage {
            events += "open:$pageIndex"
            return object : PdfNativePage {
                override val width = 300; override val height = 600
                override fun render(bitmap: Bitmap) = error("No render or OCR")
                override fun selectText(start: PdfTextPoint, stop: PdfTextPoint): PdfSelectedText? {
                    assertEquals(P.anchor.start, nativePdfPoint(start, width, height)); assertEquals(P.anchor.stop, nativePdfPoint(stop, width, height))
                    events += "select"; onSelect(); return selected
                }
                override fun close() { events += "page-close" }
            }
        }
        override fun close() { events += "document-close"; onClose() }
    }
    private suspend fun fixture(block: suspend (File, com.yaprak.reader.domain.library.Book, Native, PdfSelectedTextSources) -> Unit) {
        val directory = Files.createTempDirectory("pdf-ai-selection-").toFile(); val file = File(directory, "source.pdf")
        file.writeText("Controlled original source bytes")
        val book = P.book.copy(byteSize = file.length(), sha256 = E.hash(file.readText()))
        val native = Native(mutableListOf(), E.text)
        val source = PdfSelectedTextSources(VerifiedBookFile(directory), { native.events += "document-open"; PdfDocument(native) }, { true })
        try { block(file, book, native, source) } finally { file.delete(); directory.delete() }
    }
    private fun request(book: com.yaprak.reader.domain.library.Book) = P.request().copy(contentSha256 = book.sha256)
    private suspend fun refused(block: suspend () -> Unit) {
        assertEquals(BookAnswerFailure.SOURCE, (runCatching { block() }.exceptionOrNull() as? BookAnswerException)?.reason)
    }
    @Test fun originalHashExactNativeTextPointsAndBoundsAreProvedBeforeReturningDetachedTextAndAwaitedClose() = runBlocking {
        fixture { _, book, native, source ->
            val result = source.read(book, request(book)); assertEquals(E.text, result.single().text)
            assertEquals(listOf("document-open", "open:2", "select", "page-close", "document-close"), native.events)
        }
    }
    @Test fun missingChangedTextPageAndGeometryNeverReturnSuccessfulSourcesAndAlwaysClose() = runBlocking {
        for (bad in listOf(null, PdfSelectedText(2, E.text), PdfSelectedText(2, E.text + "!", P.anchor),
            PdfSelectedText(1, E.text, P.anchor), PdfSelectedText(2, E.text, PdfSourceAnchor(2, 300, 600, P.anchor.start, P.anchor.stop,
                listOf(PdfTextBounds(61.0, 180.0, 211.0, 200.0)))))) fixture { _, book, native, source ->
            native.selected = bad; refused { source.read(book, request(book)) }
            assertEquals("document-close", native.events.last()); assertTrue(native.events.contains("page-close"))
        }
    }
    @Test fun changedOriginalBeforeOrDuringProofAndNativeCloseFailureCannotProduceDetachedSources() = runBlocking {
        fixture { file, book, native, source ->
            file.writeText("x".repeat(book.byteSize.toInt())); refused { source.read(book, request(book)) }; assertTrue(native.events.isEmpty())
        }
        fixture { file, book, native, source ->
            native.onSelect = { file.writeText("x".repeat(book.byteSize.toInt())) }; refused { source.read(book, request(book)) }
            assertEquals("document-close", native.events.last())
        }
        fixture { _, book, native, source ->
            native.onClose = { throw IOException("Close failed") }; refused { source.read(book, request(book)) }
            assertEquals("document-close", native.events.last())
        }
    }
    @Test fun absentCapabilityWrongPageCountAndWrongKindFailWithoutNativeSelection() = runBlocking {
        fixture { file, book, native, source ->
            val unavailable = PdfSelectedTextSources(VerifiedBookFile(file.parentFile!!), { error("No native open") }, { false })
            refused { unavailable.read(book, request(book)) }; assertTrue(native.events.isEmpty())
            refused { source.read(book, E.request().copy(contentSha256 = book.sha256)) }; assertTrue(native.events.isEmpty())
            native.pageCount = 2; refused { source.read(book, request(book)) }
            assertEquals(listOf("document-open", "document-close"), native.events)
        }
    }
    @Test fun cancellationDuringNativeSelectionPreservesCallerCancellationAndAwaitsPageAndDocumentDisposal() = runBlocking {
        fixture { _, book, native, source ->
            val job = Job(); native.onSelect = { job.cancel() }
            val operation = async(job) { source.read(book, request(book)) }
            assertTrue(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
            assertEquals("document-close", native.events.last()); assertTrue(native.events.contains("page-close"))
        }
    }
}
