package com.yaprak.reader.formats.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument as NativePdfWriter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingSnapshot
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production PdfRenderer thumbnails. Requires a connected Android device or emulator. */
@RunWith(AndroidJUnit4::class)
class PdfThumbnailsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun real_original_pdf_pages_produce_bounded_distinct_ink_and_retained_images_survive_close() = runBlocking {
        val file = File(instrumentation.targetContext.cacheDir, "thumb-${UUID.randomUUID()}.pdf")
        withContext(Dispatchers.IO) {
            instrumentation.context.assets.open("books/two-pages.pdf").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        val document = withContext(Dispatchers.IO) { PdfDocument.open(file) }
        val source = DocumentPdfThumbnailsSource(document)
        try {
            assertEquals(2, source.pageCount)
            val first = source.render(0); val second = source.render(1)
            assertSame(first, source.render(0))
            for (bitmap in listOf(first, second)) {
                assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
                assertTrue(bitmap.width in 1..240 && bitmap.height in 1..320)
                assertTrue(bitmap.allocationByteCount <= MAX_PDF_THUMBNAIL_BYTES)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Actual PDF page ink must be present", pixels.any { it != Color.WHITE })
            }
            assertFalse("Distinct fixture pages must not reuse the wrong cached image", first.sameAs(second))
            source.close(); document.close()
            assertFalse(first.isRecycled); assertFalse(second.isRecycled)
            assertTrue(runCatching { source.render(0) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { document.renderThumbnail(0) }.exceptionOrNull() is IllegalStateException)
        } finally { source.close(); document.close(); file.delete() }
    }

    @Test fun tiny_vector_page_upscales_in_original_colors_without_changing_reader_progress_or_night_mode() = runBlocking {
        withContext(Dispatchers.Main) {
        val file = File(instrumentation.targetContext.cacheDir, "vector-${UUID.randomUUID()}.pdf")
        withContext(Dispatchers.IO) { writeVectorFixture(file) }
        val document = withContext(Dispatchers.IO) { PdfDocument.open(file) }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val book = Book("thumbnail-device", "Vector pages", null, BookFormat.PDF, file.name,
            file.name, file.length(), hash, 0, 2)
        val session = PdfReaderSession(book, document, PdfPosition.encode(1))
        val surface = session.createSurface(session.resume, false, {}, {})
        val snapshots = mutableListOf<ReadingSnapshot>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            surface.observe({ true }) { snapshots += it }
        }
        try {
            val source = requireNotNull(surface.pdfThumbnails)
            val first = source.render(0)
            assertEquals(240, first.width); assertEquals(320, first.height)
            assertEquals(Color.WHITE, first.getPixel(0, 0))
            assertEquals(Color.RED, first.getPixel(80, 80))
            surface.pdfNightMode!!.apply(true)
            assertSame(first, source.render(0))
            assertEquals(Color.RED, first.getPixel(80, 80))
            val second = source.render(1)
            assertEquals(Color.BLUE, second.getPixel(80, 80))
            val fullPage = document.render(0, 120)
            try { assertTrue("Thumbnail must retain the full native page colors", first.sameAs(fullPage)) }
            finally { fullPage.recycle() }
            assertNull(surface.pageNavigator!!.currentPage)
            assertNull(surface.pageNavigator!!.captureStablePage())
            assertTrue(snapshots.isEmpty())
            assertFalse("Previewing page zero must leave requested page one unchanged", surface.forward())
            session.close()
            assertEquals(Color.RED, first.getPixel(80, 80))
            assertTrue(runCatching { source.render(1) }.exceptionOrNull() is IllegalStateException)
        } finally { observer.cancelAndJoin(); session.close(); file.delete() }
        }
    }

    private fun writeVectorFixture(file: File) {
        val writer = NativePdfWriter()
        try {
            repeat(2) { index ->
                val page = writer.startPage(NativePdfWriter.PageInfo.Builder(6, 8, index + 1).create())
                try {
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawRect(1f, 1f, 3f, 6f, Paint().apply {
                        color = if (index == 0) Color.RED else Color.BLUE
                    })
                } finally { writer.finishPage(page) }
            }
            file.outputStream().use(writer::writeTo)
        } finally { writer.close() }
    }
}
