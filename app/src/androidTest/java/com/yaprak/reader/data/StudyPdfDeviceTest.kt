package com.yaprak.reader.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.data.exporting.AndroidStudyPdfRenderer
import com.yaprak.reader.data.exporting.StudyExportBook
import com.yaprak.reader.data.exporting.StudyNoteRecord
import com.yaprak.reader.data.exporting.StudyPassageRecord
import com.yaprak.reader.data.exporting.StudyRecordsSnapshot
import com.yaprak.reader.data.exporting.StudyWordRecord
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.MAX_STUDY_EXPORT_BYTES
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.reader.SourceTextAnchor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real production PdfDocument serialization and Android PdfRenderer; requires a device. */
@RunWith(AndroidJUnit4::class)
class StudyPdfDeviceTest {
    @Test fun production_pdf_opens_and_renders_all_pages_and_four_actual_highlight_colors() = runBlocking {
        val text = "Kaynak alıntı — İstanbul 🌿"
        val longNote = ("İstanbul 🌿 العربية עברית uzun literal <script> [link](url)\n".repeat(30)).take(1900)
        val snapshot = StudyRecordsSnapshot(
            StudyExportBook("İstanbul — Çalışma kitabı", "Çığ Öztürk", BookFormat.EPUB),
            notes = List(8) { StudyNoteRecord("Not ${it + 1}\n$longNote") },
            words = listOf(StudyWordRecord("dependency injection", "bağımlılık enjeksiyonu")),
            passages = EpubHighlightColor.entries.map { color ->
                StudyPassageRecord(text, SourceTextAnchor("OPS/chapter.xhtml", 0, text.length, "a".repeat(64)),
                    color, "Bağlı not ${color.name} — İğışÇöü 😀", 2)
            },
        )
        val bytes = AndroidStudyPdfRenderer().render(snapshot)
        assertTrue(bytes.size in 1..MAX_STUDY_EXPORT_BYTES)
        assertEquals("%PDF-", bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII))
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "study-pdf-${UUID.randomUUID()}.pdf")
        try {
            withContext(Dispatchers.IO) {
                file.writeBytes(bytes)
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = try { PdfRenderer(descriptor) } catch (failure: Throwable) {
                    descriptor.close(); throw failure
                }
                renderer.use {
                    assertTrue(renderer.pageCount in 2..200)
                    val tints = listOf(Color.rgb(255, 248, 190), Color.rgb(212, 245, 220),
                        Color.rgb(215, 235, 255), Color.rgb(255, 220, 235))
                    val counts = IntArray(tints.size)
                    for (index in 0 until renderer.pageCount) renderer.openPage(index).use { page ->
                        assertEquals(595, page.width); assertEquals(842, page.height)
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            assertTrue("Every output page contains actual content", pixels.any { color ->
                                Color.red(color) < 100 && Color.green(color) < 100 && Color.blue(color) < 100
                            })
                            pixels.forEach { pixel -> tints.indexOf(pixel).takeIf { it >= 0 }?.let { counts[it]++ } }
                        } finally { bitmap.recycle() }
                    }
                    counts.forEachIndexed { index, count ->
                        assertTrue("Native PDF lost ${EpubHighlightColor.entries[index]} paint", count > 100)
                    }
                }
            }
        } finally { file.delete() }
    }

    @Test fun repeated_real_pdf_exports_create_independent_closed_documents() = runBlocking {
        val renderer = AndroidStudyPdfRenderer()
        val snapshot = StudyRecordsSnapshot(StudyExportBook("PDF kitabı", null, BookFormat.PDF, 12),
            notes = listOf(StudyNoteRecord("Gerçek sayfa notu — İğışÇöü", 11)))
        repeat(2) {
            val bytes = renderer.render(snapshot)
            val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "study-pdf-reopen-${UUID.randomUUID()}.pdf")
            try {
                withContext(Dispatchers.IO) {
                    file.writeBytes(bytes)
                    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val pdf = try { PdfRenderer(descriptor) } catch (failure: Throwable) {
                        descriptor.close(); throw failure
                    }
                    pdf.use { assertEquals(1, it.pageCount) }
                }
            } finally { file.delete() }
        }
    }
}
