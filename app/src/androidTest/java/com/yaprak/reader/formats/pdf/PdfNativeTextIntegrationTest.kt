package com.yaprak.reader.formats.pdf

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual locally created PDF bytes and the Android native engine, not a mocked extraction source. */
@RunWith(AndroidJUnit4::class)
class PdfNativeTextIntegrationTest {
    @Test fun nativeTextFromTwoPdfPagesProducesRealSearchTargets() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "native-text-${UUID.randomUUID()}.pdf")
        withContext(Dispatchers.IO) { makePdf(file) }
        val original = file.readBytes()
        val document = withContext(Dispatchers.IO) { PdfDocument.open(file) }
        try {
            assertTrue(document.textAvailable)
            val first = document.extractText(0)
            assertTrue(first.text, first.text.contains("First alpha"))
            val search = PdfBookSearch(document, navigate = { false })
            val results = search.find("alpha")
            assertEquals(2, results.hits.size)
            assertEquals(listOf(0, 1), results.hits.map { PdfPosition.decodeOrNull(it.location, 2) })
            assertFalse(results.truncated)
            assertArrayEquals(original, file.readBytes())
        } finally { document.close(); file.delete() }
    }
    private fun makePdf(file: File) {
        // SDK35 host PdfDocument writer is unported; these original PDF bytes use real xref offsets.
        val first = "BT /F1 18 Tf 20 200 Td (First alpha) Tj ET\n"
        val second = "BT /F1 18 Tf 20 200 Td (Second alpha) Tj ET\n"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Count 2 /Kids [3 0 R 4 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length ${first.length} >>\nstream\n${first}endstream",
            "<< /Length ${second.length} >>\nstream\n${second}endstream",
        )
        val bytes = java.io.ByteArrayOutputStream()
        fun append(text: String) { bytes.write(text.toByteArray(Charsets.US_ASCII)) }
        val offsets = mutableListOf<Int>()
        append("%PDF-1.4\n")
        objects.forEachIndexed { index, body -> offsets += bytes.size(); append("${index + 1} 0 obj\n$body\nendobj\n") }
        val xref = bytes.size()
        append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { append("%010d 00000 n \n".format(java.util.Locale.ROOT, it)) }
        append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        file.writeBytes(bytes.toByteArray())
    }
}
