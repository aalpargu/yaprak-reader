package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.formats.pdf.PdfDocument
import com.yaprak.reader.formats.pdf.PdfPosition
import com.yaprak.reader.formats.pdf.PdfReaderSession
import com.yaprak.reader.reader.PdfPageCrop
import kotlinx.coroutines.cancelAndJoin
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
class PdfCropSessionTest {
    @Test fun `surface crop controller leaves native thumbnail cache navigation and snapshot flow unchanged`() = runTest {
        val native = PdfNativeFixture()
        val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        val book = Book("book", "PDF crop", null, BookFormat.PDF, "book.pdf", "book.pdf", 1,
            "a".repeat(64), 0, 2)
        val session = PdfReaderSession(book, document, PdfPosition.encode(1))
        val surface = session.createSurface(session.resume, false, {}, {})
        val snapshots = mutableListOf<ReadingSnapshot>()
        val observer = backgroundScope.launch { surface.observe({ true }) { snapshots += it } }
        try {
            runCurrent()
            val source = requireNotNull(surface.pdfThumbnails)
            val crop = requireNotNull(surface.pdfCrop)
            val preview = source.render(0)
            val original = IntArray(preview.width * preview.height).also {
                preview.getPixels(it, 0, preview.width, 0, 0, preview.width, preview.height)
            }
            val nativeEvents = native.events.toList()
            crop.apply(PdfPageCrop(20, 20)); crop.apply(PdfPageCrop(20, 20)); crop.apply(PdfPageCrop())
            runCurrent()
            assertSame(preview, source.render(0)); assertEquals(nativeEvents, native.events.toList())
            val after = IntArray(preview.width * preview.height).also {
                preview.getPixels(it, 0, preview.width, 0, 0, preview.width, preview.height)
            }
            assertArrayEquals(original, after)
            assertEquals(2, source.pageCount); assertEquals(2, surface.pageNavigator!!.pageCount)
            assertNull(surface.pageNavigator!!.currentPage); assertNull(surface.pageNavigator!!.captureStablePage())
            assertNull(surface.pageLabel.first()); assertFalse(surface.renderingFailed.first())
            assertTrue(snapshots.isEmpty())
            assertFalse("Requested page one must remain the last page", surface.forward())
        } finally { observer.cancelAndJoin(); session.close(); advanceUntilIdle() }
    }
}
