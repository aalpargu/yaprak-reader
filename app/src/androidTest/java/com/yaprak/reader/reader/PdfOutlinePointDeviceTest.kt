package com.yaprak.reader.reader

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import android.graphics.Bitmap
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

/** Real crop/rotation metadata, independent native pixels, production Contents, paint, history and Room. */
@RunWith(AndroidJUnit4::class)
class PdfOutlinePointDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var lastFrameDiagnostic = "No frame requested"

    @Test fun inheritedCropXyzAndNamedFitHNavigateAndPersistInVerticalReader() = runBlocking {
        exercise(false, PdfNavigationMode.VERTICAL_SCROLL)
    }

    @Test fun rotatedXyzAndNamedPointNavigateAndPersistInHorizontalReader() = runBlocking {
        exercise(true, PdfNavigationMode.HORIZONTAL_SCROLL)
    }

    private suspend fun exercise(rotated: Boolean, mode: PdfNavigationMode) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as YaprakApplication).container
        withTimeout(30_000) { container.startup.awaitReady() }
        val id = UUID.randomUUID().toString()
        val original = linkedPdf(rotated)
        val file = File(context.filesDir, "books/$id.pdf").apply { parentFile!!.mkdirs() }
        withContext(Dispatchers.IO) { file.writeBytes(original) }
        val hash = MessageDigest.getInstance("SHA-256").digest(original)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        val book = Book(id, "PDF outline point device", null, BookFormat.PDF, "outline-point.pdf",
            file.name, file.length(), hash, System.currentTimeMillis(), 3)
        var added = false
        val prefix = if (rotated) "rotated-horizontal" else "cropped-vertical"
        try {
            // The parser's coordinate conversion is not the pixel oracle: render the original
            // independently and require its blue/purple strips at 40%/60% of the actual paper.
            val native = withContext(Dispatchers.IO) { PdfDocument.open(file) }
            try {
                val dimensions = native.pageDimensions(1)
                assertEquals(if (rotated) 180 else 200, dimensions.width)
                assertEquals(800, dimensions.height)
                val image = native.render(1, 400)
                try {
                    assertEquals(android.graphics.Color.BLUE, image.getPixel(image.width / 2, (image.height * .4).toInt()))
                    assertEquals(android.graphics.Color.MAGENTA, image.getPixel(image.width / 2, (image.height * .6).toInt()))
                } finally { image.recycle() }
            } finally { native.closeAndAwait() }
            container.library.add(book); added = true
            container.bookPdfViewPreferences.saveFit(id, hash, PdfFitPreference.WIDTH)
            container.bookPdfViewPreferences.saveNavigation(id, hash, mode)
            container.bookPdfViewPreferences.saveSpread(id, hash, false)
            container.bookPdfViewPreferences.saveCrop(id, hash, 0, 0)
            val first = PdfResumePosition(1, 400_000)
            val second = PdfResumePosition(1, 600_000)
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                await("source native page painted") { frame(scenario)?.pageIndex == 0 }
                scenario.onActivity { activity ->
                    val reader = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>().single()
                    assertEquals("Actual reader navigation mode", mode, reader.navigationMode.value)
                }
                val before = requireNotNull(frame(scenario))
                openContents(scenario)
                await("direct original point appears") { compose.onAllNodesWithText("Direct point").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("External web").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithText("Direct point").performScrollTo().performClick()
                modalClosed()
                await("direct original point painted") { same(first, frame(scenario)) }
                await("direct painted point persisted") { same(first, PdfPosition.decodeResumeOrNull(container.reading.resume(id, hash), 3)) }
                capture("$prefix-direct")
                showReaderControls(compose, scenario)
                compose.onNodeWithText("İçerik").performClick()
                compose.onNodeWithText("Atlamadan önceki konuma dön").performScrollTo().performClick()
                await("real original point restored by jump history") { same(before, frame(scenario)) }
                openContents(scenario)
                await("named original point appears") { compose.onAllNodesWithText("Named point").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Named point").performScrollTo().performClick()
                modalClosed()
                await("named original point painted") { same(second, frame(scenario)) }
                await("named painted point persisted") { same(second, PdfPosition.decodeResumeOrNull(container.reading.resume(id, hash), 3)) }
                if (compose.onAllNodesWithText("Gizle").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Gizle").performClick()
                await("named native point after chrome hides") { same(second, frame(scenario)) }
                capture("$prefix-named")
                scenario.recreate()
                await("Activity recreated at actual original point") { same(second, frame(scenario)) }
            }
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                await("book reopened at durable original point") { same(second, frame(scenario)) }
            }
            assertArrayEquals(original, withContext(Dispatchers.IO) { file.readBytes() })
        } finally {
            if (added) {
                var removal: BookRemovalResult = BookRemovalResult.InUse
                await("owned book lease released") {
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

    private fun same(expected: PdfResumePosition, actual: PdfResumePosition?) =
        actual?.pageIndex == expected.pageIndex && actual.anchorY != null && expected.anchorY != null && abs(actual.anchorY - expected.anchorY) <= 4500

    private fun modalClosed() {
        compose.onNodeWithText("Kapat").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
    }

    private fun openContents(scenario: ActivityScenario<ReaderActivity>) {
        showReaderControls(compose, scenario)
        compose.onNodeWithText("İçerik").performClick()
        compose.onNodeWithText("İçindekiler").performScrollTo().performClick()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "yaprak-pdf-outline-point-0.184")
        assertTrue(directory.isDirectory || directory.mkdirs())
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // Compose idle does not wait for Android's dialog window exit animation. Keep the
        // real controls/target assertions above, then await a quiet accessibility window.
        automation.waitForIdle(500, 5_000)
        val bitmap = checkNotNull(automation.takeScreenshot())
        try { File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }

    private fun frame(scenario: ActivityScenario<ReaderActivity>): PdfResumePosition? {
        var result: PdfResumePosition? = null
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>()
                .singleOrNull() ?: return@onActivity
            val point = fragment.drawnPosition.value
            lastFrameDiagnostic = "point=$point; bookmark=${fragment.bookmarkPage}; mode=${fragment.navigationMode.value}; resumed=${fragment.isResumed}; failed=${fragment.renderFailed.value}; shown=${fragment.view?.isShown}; focus=${fragment.view?.hasWindowFocus()}"
            if (point == null) return@onActivity
            if (fragment.scrolling.value && fragment.bookmarkPage == point.pageIndex) result = point
        }
        return result
    }
    private suspend fun await(reason: String, condition: suspend () -> Boolean) {
        // Fixed synthetic test-stage names and numeric state only; no original book text is logged.
        android.util.Log.i("YaprakOutlinePointTest", "Await: $reason")
        try {
            withTimeout(20_000) {
                while (true) {
                    compose.waitForIdle()
                    if (condition()) break
                    delay(50)
                }
            }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError("Timed out: $reason; $lastFrameDiagnostic", failure)
        }
        assertTrue(reason, condition())
        android.util.Log.i("YaprakOutlinePointTest", "Passed: $reason; $lastFrameDiagnostic")
    }

    private fun linkedPdf(rotated: Boolean): ByteArray {
        val geometry = if (rotated) "/MediaBox [10 20 1010 220] /CropBox [110 30 910 210] /Rotate 90"
            else "/MediaBox [10 20 230 1020] /CropBox [20 120 220 920]"
        val direct = if (rotated) "[4 0 R /XYZ 430 210 0]" else "[4 0 R /XYZ 20 600 0]"
        val named = if (rotated) "[4 0 R /XYZ 590 210 null]" else "[4 0 R /FitH 440]"
        val strips = if (rotated) "0 0 1 rg 424 30 12 180 re f\n1 0 1 rg 584 30 12 180 re f\n"
            else "0 0 1 rg 20 594 200 12 re f\n1 0 1 rg 20 434 200 12 re f\n"
        val streams = listOf("BT /F1 12 Tf 130 170 Td (Source) Tj ET\n", strips,
            "BT /F1 12 Tf 130 170 Td (Following page) Tj ET\n")
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /Names << /Dests << /Names [(later) $named] >> >> /Outlines 10 0 R >>",
            "<< /Type /Pages /Count 3 /Kids [3 0 R 4 0 R 5 0 R] $geometry >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 6 0 R >> >> /Contents 7 0 R >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 6 0 R >> >> /Contents 8 0 R >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 6 0 R >> >> /Contents 9 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        ) + streams.map { "<< /Length ${it.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${it}endstream" } + listOf(
            "<< /Type /Outlines /First 11 0 R /Last 13 0 R /Count 3 >>",
            "<< /Title (Direct point) /Parent 10 0 R /Dest $direct /Next 12 0 R >>",
            "<< /Title (Named point) /Parent 10 0 R /Dest (later) /Prev 11 0 R /Next 13 0 R >>",
            "<< /Title (External web) /Parent 10 0 R /Prev 12 0 R /A << /S /URI /URI (https://example.invalid) >> >>",
        )
        val bytes = ByteArrayOutputStream()
        fun append(text: String) { bytes.write(text.toByteArray(Charsets.US_ASCII)) }
        val offsets = mutableListOf<Int>()
        append("%PDF-1.4\n")
        objects.forEachIndexed { index, content ->
            offsets += bytes.size(); append("${index + 1} 0 obj\n$content\nendobj\n")
        }
        val xref = bytes.size()
        append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { append("%010d 00000 n \n".format(Locale.ROOT, it)) }
        append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return bytes.toByteArray()
    }
}
