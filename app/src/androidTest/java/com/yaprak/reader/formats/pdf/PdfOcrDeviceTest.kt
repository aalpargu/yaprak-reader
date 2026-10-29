package com.yaprak.reader.formats.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real bundled Latin model and native PDF raster. Must run on a device; compilation proves no recognition quality. */
@RunWith(AndroidJUnit4::class)
class PdfOcrDeviceTest {
    private fun textImage() = Bitmap.createBitmap(800, 240, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        Canvas(this).drawText("YAPRAK 2026", 40f, 140f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f })
    }
    @Test fun bundledLatinModelRecognizesAnOriginalBitmapWithoutTakingOwnership() = runBlocking {
        val image = textImage()
        try {
            val text = withTimeout(60000) { BundledLatinPdfOcrRecognizer().recognize(image) }!!
            assertTrue(text.contains("YAPRAK")); assertTrue(text.contains("2026")); assertFalse(image.isRecycled)
            assertEquals(Color.WHITE, image.getPixel(0, 0))
        } finally { image.recycle() }
    }
    @Test fun imageOnlyPdfPageCanProduceOptionalOcrTextWithoutAPdfAnchorOrFileWrite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "ocr-device-${UUID.randomUUID()}").apply { check(mkdir()) }
        val file = File(directory, "scan.pdf"); val image = textImage()
        try {
            val writer = android.graphics.pdf.PdfDocument()
            try {
                val page = writer.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(800, 240, 1).create())
                page.canvas.drawBitmap(image, 0f, 0f, null); writer.finishPage(page)
                file.outputStream().use { writer.writeTo(it) }
            } finally { writer.close() }
            val bytes = file.readBytes()
            val book = Book(UUID.randomUUID().toString(), "OCR test", null, BookFormat.PDF, file.name, file.name,
                bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, 1, pageCount = 1)
            withContext(Dispatchers.IO) { PdfDocument.open(file) }.use { document ->
                val source = DocumentPdfOcrSource(book, document, VerifiedBookFile(directory), BundledLatinPdfOcrRecognizer(), { 0 }, Mutex())
                assertNull(source.capture()); assertTrue(source.prepare(0))
                val text = withTimeout(60000) { source.capture() }!!
                assertTrue(text.text.contains("YAPRAK")); assertTrue(text.text.contains("2026"))
                assertEquals(0, text.pdfPageIndex); assertTrue(text.pdfOcr); assertTrue(source.isCurrent(text.sourceSelectionId))
                assertNull(text.pdfSourceAnchor); assertNull(text.sourceAnchor); assertNull(text.rangeCandidate)
                assertArrayEquals(bytes, file.readBytes()); source.clear(); assertFalse(source.isCurrent(text.sourceSelectionId))
            }
        } finally { image.recycle(); directory.deleteRecursively() }
    }
}
