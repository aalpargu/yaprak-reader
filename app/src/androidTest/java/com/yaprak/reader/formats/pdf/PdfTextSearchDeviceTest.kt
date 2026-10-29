package com.yaprak.reader.formats.pdf

import android.graphics.Paint
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual native PDF extraction acceptance; needs an Android15+ device. */
@RunWith(AndroidJUnit4::class)
class PdfTextSearchDeviceTest {
    @Test fun realPdfTextSearchHasTwoPageTargetsAndLeavesOriginalBytesUnchanged() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "search-${UUID.randomUUID()}.pdf")
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
            val source = PdfBookSearch(document, navigate = { false })
            val result = source.find("alpha")
            assertEquals(listOf(0, 1), result.hits.map { PdfPosition.decodeOrNull(it.location, 2) })
            assertEquals(2, result.hits.size); assertFalse(result.truncated)
            assertTrue(source.find("absent").hits.isEmpty())
            assertArrayEquals(bytes, file.readBytes())
        } finally { document.close(); file.delete() }
    }
}
