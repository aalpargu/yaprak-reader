package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.yaprak.reader.formats.pdf.*
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The backend seam tests native-call orchestration; these fixtures are not PDF decoding evidence. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PdfDocumentLifecycleTest {
    @Test fun originalPageDimensionsUseTheNativeLeaseAndCloseWithoutAllocatingOrRendering() = runTest {
        val native = PdfNativeFixture(width = 123, height = 456)
        val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        assertEquals(PdfPageDimensions(123, 456), document.pageDimensions(1))
        assertEquals(listOf("open:1", "page-close:1"), native.events); assertTrue(native.bitmaps.isEmpty())
        assertTrue(runCatching { document.pageDimensions(2) }.exceptionOrNull() is IllegalArgumentException)
        document.close(); advanceUntilIdle()
        assertTrue(runCatching { document.pageDimensions(0) }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, native.closes)
        val malformed = PdfNativeFixture(width = 0)
        val invalid = PdfDocument(malformed, StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { invalid.pageDimensions(0) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf("open:0", "page-close:0"), malformed.events); assertTrue(malformed.bitmaps.isEmpty())
        invalid.close(); advanceUntilIdle()
    }
    @Test fun dimensionRequestCancelledBehindANativeRenderNeverOpensAnotherPage() = runTest {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val disposed = CompletableDeferred<Unit>()
        val native = PdfNativeFixture().apply {
            onRender = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            onClose = { disposed.complete(Unit) }
        }
        val document = PdfDocument(native, Dispatchers.IO)
        val rendering = async { document.render(0, 100) }; runCurrent()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val dimensions = async { document.pageDimensions(1) }; runCurrent(); dimensions.cancelAndJoin()
            assertEquals(listOf("open:0", "render:0"), native.events)
            release.countDown(); rendering.await().recycle()
            assertEquals(listOf("open:0", "render:0", "page-close:0"), native.events)
        } finally { release.countDown(); document.close(); disposed.await() }
    }
    @Test fun `main and thumbnail render use real ARGB buffers with white initialized original colors`() = runTest {
        val native = PdfNativeFixture()
        val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        try {
            val main = document.render(0, 400)
            val thumbnail = document.renderThumbnail(1)
            assertEquals(800, main.width)
            assertEquals(240, thumbnail.width); assertEquals(310, thumbnail.height)
            assertEquals(Bitmap.Config.ARGB_8888, thumbnail.config)
            assertEquals(Color.RED, main.getPixel(0, 0))
            assertEquals(Color.BLUE, thumbnail.getPixel(0, 0))
            assertEquals(Color.WHITE, thumbnail.getPixel(1, 1))
            assertEquals(listOf("open:0", "render:0", "page-close:0", "open:1", "render:1", "page-close:1"), native.events)
            main.recycle(); thumbnail.recycle()
        } finally { document.close(); advanceUntilIdle() }
        assertEquals(1, native.closes)
    }

    @Test fun `invalid page requests never open native pages`() = runTest {
        val native = PdfNativeFixture()
        val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        for (page in listOf(-1, 2, Int.MAX_VALUE)) {
            assertTrue(runCatching { document.renderThumbnail(page) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { document.render(page, 400) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(native.events.isEmpty())
        document.close(); advanceUntilIdle()
    }

    @Test fun `invalid native dimensions close the page without allocating a buffer`() = runTest {
        val native = PdfNativeFixture(width = 0)
        val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { document.renderThumbnail(0) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf("open:0", "page-close:0"), native.events)
        assertTrue(native.bitmaps.isEmpty())
        document.close(); advanceUntilIdle()
    }

    @Test fun `native draw failure closes page and recycles only its undelivered buffer`() = runTest {
        val native = PdfNativeFixture().apply { onRender = { throw IOException("draw failed") } }
        val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { document.renderThumbnail(0) }.exceptionOrNull() is IOException)
        assertTrue(native.bitmaps.single().isRecycled)
        assertEquals(listOf("open:0", "render:0", "page-close:0"), native.events)
        native.onRender = {}
        val retry = document.renderThumbnail(0)
        assertFalse(retry.isRecycled)
        document.close(); advanceUntilIdle()
        assertFalse(retry.isRecycled)
        retry.recycle()
    }

    @Test fun `native page cleanup failure stays suppressed below the original draw failure`() = runTest {
        val native = PdfNativeFixture().apply {
            onRender = { throw IOException("draw failed") }
            onPageClose = { throw IOException("page cleanup failed") }
        }
        val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        val error = runCatching { document.renderThumbnail(0) }.exceptionOrNull()!!
        val errors = exceptionGraph(error)
        assertTrue(errors.any { it.message == "draw failed" })
        assertTrue(errors.any { it.message == "page cleanup failed" })
        assertTrue(native.bitmaps.single().isRecycled)
        document.close(); advanceUntilIdle()
    }

    @Test fun `cancellation during native render closes page and rejects the newly drawn buffer`() = runTest {
        val native = PdfNativeFixture()
        val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        val request = async { document.renderThumbnail(0) }
        native.onRender = { request.cancel(CancellationException("native request cancelled")) }
        runCurrent()
        assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("open:0", "render:0", "page-close:0"), native.events)
        assertTrue(native.bitmaps.single().isRecycled)
        document.close(); advanceUntilIdle()
    }

    @Test fun `prompt cancellation after native completion but before IO hand back recycles its buffer`() = runTest {
        val native = PdfNativeFixture()
        val io = QueuedPdfDispatcher()
        val document = PdfDocument(native, io)
        val request = async { document.renderThumbnail(0) }
        runCurrent()
        io.runNext()
        assertEquals(listOf("open:0", "render:0", "page-close:0"), native.events)
        assertFalse(native.bitmaps.single().isRecycled)
        request.cancel()
        runCurrent()
        assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
        assertTrue(native.bitmaps.single().isRecycled)
        document.close(); io.runNext()
        assertEquals(1, native.closes)
    }

    @Test fun `a native render and thumbnail share one page lease even on different IO workers`() = runTest {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val disposed = CompletableDeferred<Unit>()
        val native = PdfNativeFixture().apply { onRender = {
            if (events.contains("render:0") && !events.contains("page-close:0")) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }; onClose = { disposed.complete(Unit) } }
        val document = PdfDocument(native, Dispatchers.IO)
        val main = async { document.render(0, 120) }
        runCurrent()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val thumbnail = async { document.renderThumbnail(1) }
            runCurrent()
            assertEquals(listOf("open:0", "render:0"), native.events)
            release.countDown()
            val first = main.await(); val second = thumbnail.await()
            assertEquals(listOf("open:0", "render:0", "page-close:0", "open:1", "render:1", "page-close:1"), native.events)
            first.recycle(); second.recycle()
        } finally { release.countDown(); document.close(); disposed.await() }
    }

    @Test fun `thumbnail cancelled while waiting on main render never opens another native page`() = runTest {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val disposed = CompletableDeferred<Unit>()
        val native = PdfNativeFixture().apply {
            onRender = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            onClose = { disposed.complete(Unit) }
        }
        val document = PdfDocument(native, Dispatchers.IO)
        val main = async { document.render(0, 120) }; runCurrent()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = async { document.renderThumbnail(1) }; runCurrent()
            queued.cancelAndJoin()
            assertTrue(queued.isCancelled)
            assertEquals(listOf("open:0", "render:0"), native.events)
            release.countDown()
            val delivered = main.await()
            assertEquals(listOf("open:0", "render:0", "page-close:0"), native.events)
            assertFalse(delivered.isRecycled); delivered.recycle()
        } finally { release.countDown(); document.close(); disposed.await() }
    }

    @Test fun `close during native draw immediately rejects new requests and disposes after page closes`() = runTest {
        val native = PdfNativeFixture()
        val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        native.onRender = { document.close(); document.close() }
        assertTrue(runCatching { document.renderThumbnail(0) }.exceptionOrNull() is IllegalStateException)
        advanceUntilIdle()
        assertEquals(listOf("open:0", "render:0", "page-close:0", "document-close"), native.events)
        assertEquals(1, native.closes); assertTrue(native.bitmaps.single().isRecycled)
        assertTrue(runCatching { document.renderThumbnail(1) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { document.render(0, 120) }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, native.closes)
    }

    private fun exceptionGraph(error: Throwable): List<Throwable> = buildList {
        fun visit(next: Throwable) {
            if (any { it === next }) return
            add(next); next.cause?.let(::visit); next.suppressed.forEach(::visit)
        }
        visit(error)
    }
}

/** Actual production PdfDocument calls this same-sized seam for host ownership/lock checks. */
internal class PdfNativeFixture(override val pageCount: Int = 2, val width: Int = 612, val height: Int = 792) : PdfNativeRenderer {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val bitmaps: MutableList<Bitmap> = Collections.synchronizedList(mutableListOf())
    var onRender: (Bitmap) -> Unit = {}
    var onPageClose: () -> Unit = {}
    var onClose: () -> Unit = {}
    private var active = false
    private var closed = false
    var closes = 0
        private set
    override fun openPage(pageIndex: Int): PdfNativePage {
        check(!closed && !active); active = true; events += "open:$pageIndex"
        return object : PdfNativePage {
            override val width get() = this@PdfNativeFixture.width
            override val height get() = this@PdfNativeFixture.height
            override fun render(bitmap: Bitmap) {
                check(active); events += "render:$pageIndex"; bitmaps += bitmap
                assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
                assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
                bitmap.setPixel(0, 0, if (pageIndex == 0) Color.RED else Color.BLUE)
                onRender(bitmap)
            }
            override fun close() {
                check(active); active = false; events += "page-close:$pageIndex"; onPageClose()
            }
        }
    }
    override fun close() {
        check(!active && !closed); closed = true; closes++; events += "document-close"; onClose()
    }
}

internal class QueuedPdfDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
    fun runNext() { check(queue.isNotEmpty()); queue.removeFirst().run() }
}
