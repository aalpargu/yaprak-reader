package com.yaprak.reader.formats.pdf

import android.graphics.Paint
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.reader.PdfTextPoint
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android15+ PDF decoder acceptance; host pointer/seam tests cannot replace this. */
@RunWith(AndroidJUnit4::class)
class PdfTextSelectionDeviceTest {
    @Test fun realNativeWordRangeAndBlankSelectionKeepTheirSourcePageAndOriginalBytes() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "selection-${UUID.randomUUID()}.pdf")
        withContext(Dispatchers.IO) {
            val writer = android.graphics.pdf.PdfDocument()
            try {
                listOf("First alpha", "Second alpha").forEachIndexed { index, text ->
                    val page = writer.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 300, index + 1).create())
                    try { page.canvas.drawText(text, 20f, 100f, Paint().apply { textSize = 18f }) }
                    finally { writer.finishPage(page) }
                }
                file.outputStream().use(writer::writeTo)
            } finally { writer.close() }
        }
        val bytes = file.readBytes()
        val document = withContext(Dispatchers.IO) { PdfDocument.open(file) }
        try {
            val word = PdfTextPoint(110.0 / 300, 94.0 / 300)
            val selected = requireNotNull(document.selectText(1, word, word))
            assertEquals(1, selected.pageIndex); assertEquals("alpha", selected.text.trim())
            // A drag selects a glyph interval, unlike a collapsed word tap. Enclose the whole owned line.
            val right = 21.0 + Paint().apply { textSize = 18f }.measureText("Second alpha")
            val range = requireNotNull(document.selectText(1, PdfTextPoint(19.0 / 300, 94.0 / 300), PdfTextPoint(right / 300, 94.0 / 300)))
            assertEquals(1, range.pageIndex); assertEquals("Second alpha", range.text.trim())
            assertNull(document.selectText(1, PdfTextPoint(.9, .9), PdfTextPoint(.9, .9)))
            assertArrayEquals(bytes, file.readBytes())
        } finally { document.close(); file.delete() }
    }
}
