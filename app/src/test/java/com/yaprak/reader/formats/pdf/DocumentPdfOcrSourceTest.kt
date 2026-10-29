package com.yaprak.reader.formats.pdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.reader.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real private bytes/bitmap with a controlled native page; no claim of PDF decoder or OCR quality. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DocumentPdfOcrSourceTest {
    private class Native : PdfNativeRenderer {
        override val pageCount = 3
        val pages = mutableListOf<Int>()
        val images = mutableListOf<Bitmap>()
        var render: (Bitmap) -> Unit = { it.setPixel(0, 0, Color.RED) }
        var closed = 0
        override fun openPage(pageIndex: Int): PdfNativePage {
            pages += pageIndex
            return object : PdfNativePage {
                override val width = 1000; override val height = 3000
                override fun render(bitmap: Bitmap) { images += bitmap; this@Native.render(bitmap) }
                override fun close() = Unit
            }
        }
        override fun close() { closed++ }
    }
    private class Fixture(val dispatcher: CoroutineDispatcher) {
        val directory = Files.createTempDirectory("yaprak-ocr-source-").toFile()
        val bytes = "Private source bytes; native is controlled".toByteArray()
        val file = File(directory, "book.pdf").apply { writeBytes(bytes) }
        val book = Book(UUID.randomUUID().toString(), "İstanbul", "Writer", BookFormat.PDF, "book.pdf", file.name,
            bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, 1, pageCount = 3)
        val native = Native()
        val document = PdfDocument(native, dispatcher)
        val mutex = Mutex()
        var page: Int? = 1
        var validates = 0; var calls = 0
        var guard: suspend (Book) -> Unit = { validates++ }
        var recognize: suspend (Bitmap) -> String? = { "  Türkçe\nEnglish 🌿  " }
        fun source(expected: Book = book) = DocumentPdfOcrSource(expected, document, VerifiedBookFile(directory),
            PdfOcrBitmapRecognizer { calls++; recognize(it) }, { page }, mutex, { guard(it) }, dispatcher)
    }
    private suspend fun TestScope.fixture(block: suspend Fixture.() -> Unit) {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try { f.block() } finally { f.document.close(); advanceUntilIdle(); f.directory.deleteRecursively() }
    }
    private fun failure(error: Throwable?, expected: PdfOcrFailure) {
        assertTrue(error is PdfOcrException); assertEquals(expected, (error as PdfOcrException).reason)
    }
    @Test fun explicitStablePageUsesOriginalBoundedColorRasterAndReturnsExactTextWithoutAnAnchor() = runTest { fixture {
        val source = source(); assertNull(source.capture()); assertTrue(native.pages.isEmpty())
        assertFalse(source.prepare(0)); assertTrue(source.prepare(1))
        recognize = {
            assertEquals(Bitmap.Config.ARGB_8888, it.config); assertTrue(it.width in 1..2048); assertEquals(2048, it.height)
            assertEquals(Color.RED, it.getPixel(0, 0)); assertEquals(Color.WHITE, it.getPixel(1, 1))
            assertTrue(mutex.tryLock()); mutex.unlock()
            "  Türkçe\nEnglish 🌿  "
        }
        val text = source.capture()!!
        assertEquals("  Türkçe\nEnglish 🌿  ", text.text); assertTrue(text.pdfOcr); assertEquals(1, text.pdfPageIndex)
        assertEquals(text.sourceSelectionId, UUID.fromString(text.sourceSelectionId).toString())
        assertTrue(source.isCurrent(text.sourceSelectionId)); assertFalse(source.isCurrent(UUID.randomUUID().toString()))
        assertNull(text.sourceAnchor); assertNull(text.pdfSourceAnchor); assertNull(text.rangeCandidate)
        assertEquals(listOf(1), native.pages); assertEquals(2, validates); assertTrue(native.images.single().isRecycled)
        assertArrayEquals(bytes, file.readBytes()); assertEquals(1, page)
    } }
    @Test fun invalidFormatPageCountAndStablePageCannotPrepareOrInvokeTheModel() = runTest { fixture {
        assertFalse(source(book.copy(format = BookFormat.EPUB)).prepare(1))
        assertFalse(source(book.copy(pageCount = null)).prepare(1))
        assertFalse(source(book.copy(pageCount = 4)).prepare(1))
        val source = source()
        for (index in listOf(-1, 0, 2, 3, Int.MAX_VALUE)) assertFalse(source.prepare(index))
        page = null; assertFalse(source.prepare(1)); assertNull(source.capture()); assertEquals(0, calls)
        assertTrue(native.pages.isEmpty())
    } }
    @Test fun closedDocumentCannotPrepareOrReturnAnOldLease() = runTest { fixture {
        val source = source(); assertTrue(source.prepare(1)); document.close()
        assertFalse(source.prepare(1)); assertFalse(source.isCurrent(null))
        assertTrue(runCatching { source.capture() }.exceptionOrNull() is CancellationException)
        assertEquals(0, calls)
    } }
    @Test fun changedSameLengthSourceIsRejectedBeforeRendering() = runTest { fixture {
        val source = source(); source.prepare(1)
        file.writeBytes(bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() })
        failure(runCatching { source.capture() }.exceptionOrNull(), PdfOcrFailure.SOURCE)
        assertTrue(native.pages.isEmpty()); assertEquals(0, calls)
    } }
    @Test fun missingAndWrongLengthFilesHaveNoModelWork() = runTest { fixture {
        val source = source(); source.prepare(1); file.delete()
        failure(runCatching { source.capture() }.exceptionOrNull(), PdfOcrFailure.SOURCE)
        file.writeText("short")
        failure(runCatching { source.capture() }.exceptionOrNull(), PdfOcrFailure.SOURCE)
        assertTrue(native.pages.isEmpty()); assertEquals(0, calls)
    } }
    @Test fun changedSourceAfterRecognitionCannotReturnTheResultAndRecyclesItsBitmap() = runTest { fixture {
        val source = source(); source.prepare(1)
        recognize = { file.writeBytes(bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }); "late text" }
        failure(runCatching { source.capture() }.exceptionOrNull(), PdfOcrFailure.SOURCE)
        assertTrue(native.images.single().isRecycled); assertEquals(1, calls)
    } }
    @Test fun recoveryOrMetadataGuardRunsOnBothSidesAndCannotReturnAStaleBook() = runTest { fixture {
        val source = source(); source.prepare(1)
        guard = { check(it == book); throw IllegalStateException("recovery pending") }
        failure(runCatching { source.capture() }.exceptionOrNull(), PdfOcrFailure.SOURCE); assertEquals(0, calls)
        var metadataCurrent = true
        guard = { check(it == book && metadataCurrent); validates++ }
        recognize = { metadataCurrent = false; "late" }
        failure(runCatching { source.capture() }.exceptionOrNull(), PdfOcrFailure.SOURCE)
        assertEquals(1, calls); assertTrue(native.images.single().isRecycled)
    } }
    @Test fun clearingOrChangingTheStablePageRejectsLateResults() = runTest { fixture {
        val source = source()
        source.prepare(1); recognize = { source.clear(); "late" }
        assertTrue(runCatching { source.capture() }.exceptionOrNull() is CancellationException)
        assertFalse(source.isCurrent(null)); assertTrue(native.images.last().isRecycled)
        source.prepare(1); recognize = { page = 2; "late" }
        assertTrue(runCatching { source.capture() }.exceptionOrNull() is CancellationException)
        assertFalse(source.isCurrent(null)); assertTrue(native.images.last().isRecycled)
    } }
    @Test fun preparingTheSamePageAgainReplacesIdentityInsteadOfAcceptingAnOldCompletion() = runTest { fixture {
        val source = source(); source.prepare(1); recognize = { assertTrue(source.prepare(1)); "old" }
        assertTrue(runCatching { source.capture() }.exceptionOrNull() is CancellationException)
        recognize = { "new" }; val text = source.capture()!!
        assertEquals("new", text.text); assertTrue(source.isCurrent(text.sourceSelectionId))
        assertTrue(native.images.all { it.isRecycled })
    } }
    @Test fun concurrentCallsAreBusyAndCancelledBorrowRemainsAliveUntilUninterruptibleWorkFinishes() = runTest { fixture {
        val source = source(); source.prepare(1)
        val finish = CompletableDeferred<String?>()
        recognize = { withContext(NonCancellable) { finish.await() } }
        val job = async { source.capture() }; runCurrent()
        val bitmap = native.images.single(); assertFalse(bitmap.isRecycled)
        job.cancel(); source.clear(); runCurrent()
        assertFalse(job.isCompleted); assertFalse(bitmap.isRecycled)
        source.prepare(1); failure(runCatching { source.capture() }.exceptionOrNull(), PdfOcrFailure.BUSY)
        finish.complete("old"); runCurrent()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException); assertTrue(bitmap.isRecycled)
        recognize = { "retry" }; assertEquals("retry", source.capture()!!.text)
    } }
    @Test fun queuedFileMutexCancellationDoesNotRenderOrBlockFutureReads() = runTest { fixture {
        val source = source(); source.prepare(1); mutex.lock()
        val job = async { source.capture() }; runCurrent(); job.cancel(); runCurrent(); mutex.unlock()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertTrue(native.pages.isEmpty()); assertEquals(0, calls)
        assertNotNull(source.capture())
    } }
    @Test fun nativeRenderFailureDoesNotCallModelAndRecyclesTheAllocatedImage() = runTest { fixture {
        val source = source(); source.prepare(1); native.render = { throw IllegalStateException("native render") }
        failure(runCatching { source.capture() }.exceptionOrNull(), PdfOcrFailure.SOURCE)
        assertEquals(0, calls); assertTrue(native.images.single().isRecycled)
        native.render = {}; assertNotNull(source.capture())
    } }
    @Test fun emptyResultIsNotAnErrorOrInventedSelectionAndInvalidOutputIsBounded() = runTest { fixture {
        val source = source(); source.prepare(1)
        for (text in listOf(null, "", " \t\r\n")) { recognize = { text }; assertNull(source.capture()) }
        recognize = { "a".repeat(5000) }; assertEquals(5000, source.capture()!!.text.length)
        recognize = { "a".repeat(5001) }; failure(runCatching { source.capture() }.exceptionOrNull(), PdfOcrFailure.LIMIT)
        recognize = { "x\uD800" }; failure(runCatching { source.capture() }.exceptionOrNull(), PdfOcrFailure.MODEL)
        recognize = { "x\u0000" }; failure(runCatching { source.capture() }.exceptionOrNull(), PdfOcrFailure.MODEL)
        assertTrue(native.images.all { it.isRecycled })
    } }
    @Test fun typedModelFailuresArePreservedAndOwnedRasterIsAlwaysRecycled() = runTest { fixture {
        val source = source(); source.prepare(1)
        for (reason in PdfOcrFailure.entries) {
            recognize = { throw PdfOcrException(reason) }
            failure(runCatching { source.capture() }.exceptionOrNull(), reason)
            assertTrue(native.images.last().isRecycled)
        }
    } }
}
