package com.yaprak.reader.reader

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookRemovalResult
import com.yaprak.reader.domain.settings.PdfFitPreference
import com.yaprak.reader.domain.settings.PdfNavigationMode
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.formats.pdf.PdfDocument
import com.yaprak.reader.formats.pdf.PdfPosition
import com.yaprak.reader.formats.pdf.PdfReaderFragment
import com.yaprak.reader.formats.pdf.PdfResumePosition
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android 15+ native GoTo parsing, reader UI, paint and original-point persistence. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class PdfPageLinksDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun originalExplicitAndNamedGoToActionsNavigateFromTheActualReaderAndPreserveJumpBack() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as YaprakApplication).container
        withTimeout(30_000) { container.startup.awaitReady() }
        val id = UUID.randomUUID().toString()
        val original = linkedPdf()
        val file = File(context.filesDir, "books/$id.pdf").apply { parentFile!!.mkdirs() }
        withContext(Dispatchers.IO) { file.writeBytes(original) }
        val hash = MessageDigest.getInstance("SHA-256").digest(original)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        val book = Book(id, "PDF internal links device", null, BookFormat.PDF, "internal-links.pdf",
            file.name, file.length(), hash, System.currentTimeMillis(), 3)
        var added = false
        try {
            // Independent native parsing asserts zero-based pages and top-origin paper points,
            // against explicit and named PDF GoTo action fixtures. Android 15's native
            // API filters out bare /Dest annotations; this does not claim those are supported.
            // URI actions must not become book destinations.
            val native = withContext(Dispatchers.IO) { PdfDocument.open(file) }
            try {
                val links = native.gotoLinks(0)
                assertEquals("Native explicit/named GoTo target pages: ${links.map { it.pageIndex }}", 2, links.size)
                assertEquals(listOf(1, 2), links.map { it.pageIndex })
                assertEquals(400.0, links[0].y, 1.0)
                assertEquals(250.0, links[1].y, 1.0)
                assertTrue(native.gotoLinks(1).isEmpty())
            } finally { native.closeAndAwait() }
            container.library.add(book); added = true
            container.bookPdfViewPreferences.saveFit(id, hash, PdfFitPreference.WIDTH)
            container.bookPdfViewPreferences.saveNavigation(id, hash, PdfNavigationMode.VERTICAL_SCROLL)
            container.bookPdfViewPreferences.saveSpread(id, hash, false)
            container.bookPdfViewPreferences.saveCrop(id, hash, 0, 0)
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                await("actual source page painted") { frame(scenario)?.pageIndex == 0 }
                val before = requireNotNull(frame(scenario))
                openPageLinks()
                compose.onNodeWithTag("pdf-page-link-1").performScrollTo().performClick()
                val first = PdfResumePosition(1, 400_000)
                await("direct destination's original point painted") { same(first, frame(scenario)) }
                await("actual painted destination persisted") {
                    same(first, PdfPosition.decodeResumeOrNull(container.reading.resume(id, hash), 3))
                }
                compose.onNodeWithText("Atlamadan önceki konuma dön").performScrollTo().performClick()
                await("original point returned through production jump history") { same(before, frame(scenario)) }
                openPageLinks()
                compose.onNodeWithTag("pdf-page-link-2").performScrollTo().performClick()
                val second = PdfResumePosition(2, 250_000)
                await("named destination painted") { same(second, frame(scenario)) }
                await("named destination persisted") {
                    same(second, PdfPosition.decodeResumeOrNull(container.reading.resume(id, hash), 3))
                }
                scenario.recreate()
                await("fresh Activity resumes actual destination point") { same(second, frame(scenario)) }
            }
            assertArrayEquals(original, withContext(Dispatchers.IO) { file.readBytes() })
        } finally {
            if (added) {
                var removal: BookRemovalResult = BookRemovalResult.InUse
                await("owned test book reader lease released") {
                    removal = container.remover.remove(id, hash)
                    removal != BookRemovalResult.InUse
                }
                assertTrue(removal is BookRemovalResult.Removed || removal == BookRemovalResult.Missing)
            }
            withContext(Dispatchers.IO) {
                check(file.parentFile!!.canonicalFile == File(context.filesDir, "books").canonicalFile)
                if (file.exists()) check(file.delete())
            }
        }
    }

    private fun openPageLinks() {
        if (compose.onAllNodesWithText("Diğer araçlar").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Diğer araçlar").performScrollTo().performClick()
        }
        compose.onNodeWithText("Sayfa bağlantıları").performScrollTo().performClick()
    }

    private fun frame(scenario: ActivityScenario<ReaderActivity>): PdfResumePosition? {
        var result: PdfResumePosition? = null
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>()
                .singleOrNull() ?: return@onActivity
            val point = fragment.drawnPosition.value ?: return@onActivity
            if (fragment.scrolling.value && fragment.bookmarkPage == point.pageIndex) result = point
        }
        return result
    }
    private fun same(expected: PdfResumePosition, actual: PdfResumePosition?) =
        actual?.pageIndex == expected.pageIndex && expected.anchorY != null && actual.anchorY != null &&
            abs(expected.anchorY - actual.anchorY) <= 7_000

    private suspend fun await(reason: String, condition: suspend () -> Boolean) {
        withTimeout(20_000) {
            while (true) {
                compose.waitForIdle()
                if (condition()) break
                delay(50)
            }
        }
        assertTrue(reason, condition())
    }

    private fun linkedPdf(): ByteArray {
        val streams = listOf(
            "BT /F1 12 Tf 20 950 Td (Internal links) Tj ET\n",
            "0 1 0 rg 0 590 200 10 re f\nBT /F1 12 Tf 20 580 Td (Direct target) Tj ET\n",
            "0 0 1 rg 0 740 200 10 re f\nBT /F1 12 Tf 20 730 Td (Named target) Tj ET\n",
        )
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /Names << /Dests << /Names [(later) [5 0 R /XYZ 0 750 0]] >> >> >>",
            "<< /Type /Pages /Count 3 /Kids [3 0 R 4 0 R 5 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 1000] /Resources << /Font << /F1 6 0 R >> >> /Contents 7 0 R /Annots [10 0 R 11 0 R 12 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 1000] /Resources << /Font << /F1 6 0 R >> >> /Contents 8 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 1000] /Resources << /Font << /F1 6 0 R >> >> /Contents 9 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        ) + streams.map { "<< /Length ${it.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${it}endstream" } + listOf(
            "<< /Type /Annot /Subtype /Link /Rect [20 880 180 920] /A << /S /GoTo /D [4 0 R /XYZ 0 600 1.5] >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [20 780 180 820] /A << /S /GoTo /D (later) >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [20 680 180 720] /A << /S /URI /URI (https://example.invalid) >> >>",
        )
        val bytes = ByteArrayOutputStream()
        fun append(text: String) { bytes.write(text.toByteArray(Charsets.US_ASCII)) }
        val offsets = mutableListOf<Int>()
        append("%PDF-1.4\n")
        objects.forEachIndexed { index, body ->
            offsets += bytes.size()
            append("${index + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = bytes.size()
        append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { append("%010d 00000 n \n".format(Locale.ROOT, it)) }
        append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return bytes.toByteArray()
    }
}
