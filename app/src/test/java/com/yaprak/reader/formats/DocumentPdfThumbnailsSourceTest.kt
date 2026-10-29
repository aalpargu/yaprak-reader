package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.formats.pdf.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class DocumentPdfThumbnailsSourceTest {
    @Test fun `session cache is limited to twelve entries and refreshes LRU without recycling retained images`() = runTest {
        val reads = mutableListOf<Int>()
        val source = DocumentPdfThumbnailsSource(20, { reads += it; bitmap(it) })
        val first = source.render(0)
        (1..11).forEach { source.render(it) }
        assertSame(first, source.render(0))
        source.render(12)
        assertSame(first, source.render(0))
        source.render(1)
        assertEquals((0..12).toList() + 1, reads)
        assertFalse(first.isRecycled); assertEquals(Color.RED, first.getPixel(0, 0))
        source.close(); source.close()
        assertFalse(first.isRecycled)
        assertTrue(runCatching { source.render(0) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `allocation byte budget evicts old references while the displayed bitmap remains usable`() = runTest {
        val reads = mutableListOf<Int>()
        val source = DocumentPdfThumbnailsSource(3, { reads += it; bitmap(it) }, maxCacheBytes = 32)
        val displayed = source.render(0)
        source.render(1); source.render(2)
        source.render(0)
        assertEquals(listOf(0, 1, 2, 0), reads)
        assertFalse(displayed.isRecycled); assertEquals(Color.RED, displayed.getPixel(0, 0))
        source.close(); assertFalse(displayed.isRecycled)
    }

    @Test fun `image larger than the configured cache is delivered without retaining it or recycling it`() = runTest {
        var reads = 0
        val source = DocumentPdfThumbnailsSource(1, { reads++; bitmap(it) }, maxCacheBytes = 1)
        val first = source.render(0); val second = source.render(0)
        assertEquals(2, reads); assertNotSame(first, second)
        assertFalse(first.isRecycled); assertFalse(second.isRecycled)
        source.close(); assertFalse(first.isRecycled); assertFalse(second.isRecycled)
    }

    @Test fun `queued cancellation never starts another native request and repeated page coalesces`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val source = DocumentPdfThumbnailsSource(2, { reads++; gate.await(); bitmap(it) })
        val first = async { source.render(0) }; runCurrent()
        val cancelled = async { source.render(1) }; runCurrent()
        cancelled.cancelAndJoin()
        val repeated = async { source.render(0) }; runCurrent()
        assertEquals(1, reads)
        gate.complete(Unit); advanceUntilIdle()
        assertSame(first.await(), repeated.await()); assertEquals(1, reads)
        assertTrue(cancelled.isCancelled)
        source.close()
    }

    @Test fun `native failures stay retryable and invalid output is recycled before cache publication`() = runTest {
        val invalid = Bitmap.createBitmap(241, 10, Bitmap.Config.ARGB_8888)
        var reads = 0
        val source = DocumentPdfThumbnailsSource(1, {
            when (++reads) { 1 -> throw IOException("retry native page"); 2 -> invalid; else -> bitmap(it) }
        })
        assertTrue(runCatching { source.render(0) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { source.render(0) }.exceptionOrNull() is IllegalStateException)
        assertTrue(invalid.isRecycled)
        val third = source.render(0)
        assertSame(third, source.render(0)); assertEquals(3, reads)
        source.close(); assertFalse(third.isRecycled)
    }

    @Test fun `source close does not wait on render and rejects a fresh late result before publication`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fresh = bitmap(1)
        val source = DocumentPdfThumbnailsSource(2, { gate.await(); fresh })
        val request = async { runCatching { source.render(1) } }; runCurrent()
        source.close()
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(request.await().exceptionOrNull() is IllegalStateException)
        assertTrue(fresh.isRecycled)
        assertTrue(runCatching { source.render(0) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `cancellation after render returns but before source publication recycles only its fresh result`() = runTest {
        val fresh = bitmap(0)
        val source = DocumentPdfThumbnailsSource(1, {
            currentCoroutineContext()[Job]!!.cancel(CancellationException("before publication")); fresh
        })
        val request = async { source.render(0) }
        runCurrent()
        assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
        assertTrue(fresh.isRecycled)
        source.close()
    }

    @Test fun `closed invalid and already cancelled requests do not enter the renderer`() = runTest {
        var reads = 0
        val source = DocumentPdfThumbnailsSource(2, { reads++; bitmap(it) })
        for (page in listOf(-1, 2, Int.MAX_VALUE)) {
            assertTrue(runCatching { source.render(page) }.exceptionOrNull() is IllegalArgumentException)
        }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext()[Job]!!.cancel(); source.render(0)
        }
        assertTrue(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
        source.close()
        assertTrue(runCatching { source.render(0) }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, reads)
    }

    @Test fun `session previews preserve requested page status and emit no reading snapshots`() = runTest {
        val native = PdfNativeFixture()
        val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        val book = Book("book", "Book", null, BookFormat.PDF, "book.pdf", "book.pdf", 1, "a".repeat(64), 0, 2)
        val session = PdfReaderSession(book, document, PdfPosition.encode(1))
        val surface = session.createSurface(session.resume, false, {}, {})
        val source = requireNotNull(surface.pdfThumbnails)
        val snapshots = mutableListOf<ReadingSnapshot>()
        val observer = launch { surface.observe({ true }) { snapshots += it } }
        runCurrent()
        assertEquals(2, source.pageCount)
        val original = source.render(0)
        surface.pdfNightMode!!.apply(true)
        assertSame(original, source.render(0))
        source.render(1)
        assertNull(surface.pageNavigator!!.currentPage)
        assertNull(surface.pageNavigator!!.captureStablePage())
        assertNull(surface.bookmarkNavigator!!.capture())
        assertNull(surface.pageLabel.first())
        assertFalse(surface.renderingFailed.first())
        assertTrue(snapshots.isEmpty())
        // Initial requested page is the last page. Previewing page zero never moves it.
        assertFalse(surface.forward())
        assertSame(source, session.createSurface(session.resume, true, {}, {}).pdfThumbnails)
        native.onClose = {
            assertTrue(kotlinx.coroutines.runBlocking { runCatching { source.render(0) }.exceptionOrNull() } is IllegalStateException)
        }
        session.close(); session.close(); advanceUntilIdle()
        assertFalse(original.isRecycled); assertEquals(Color.RED, original.getPixel(0, 0))
        assertEquals(1, native.closes)
        observer.cancelAndJoin()
    }

    private fun bitmap(page: Int) = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
        eraseColor(if (page == 0) Color.RED else Color.BLUE)
    }
}
