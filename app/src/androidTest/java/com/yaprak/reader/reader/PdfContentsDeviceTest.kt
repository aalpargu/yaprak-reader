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

/** Actual PDF outline bytes, original-file verification, reader paint, deliberate jump history and Room. */
@RunWith(AndroidJUnit4::class)
class PdfContentsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun actualOutlineDirectAndNamedPagesNavigateAndRestoreThroughTheReader() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as YaprakApplication).container
        withTimeout(30_000) { container.startup.awaitReady() }
        val id = UUID.randomUUID().toString()
        val original = linkedPdf()
        val file = File(context.filesDir, "books/$id.pdf").apply { parentFile!!.mkdirs() }
        withContext(Dispatchers.IO) { file.writeBytes(original) }
        val hash = MessageDigest.getInstance("SHA-256").digest(original)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        val book = Book(id, "PDF contents device", null, BookFormat.PDF, "contents.pdf",
            file.name, file.length(), hash, System.currentTimeMillis(), 3)
        var added = false
        try {
            container.library.add(book); added = true
            container.bookPdfViewPreferences.saveFit(id, hash, PdfFitPreference.WIDTH)
            container.bookPdfViewPreferences.saveNavigation(id, hash, PdfNavigationMode.VERTICAL_SCROLL)
            container.bookPdfViewPreferences.saveSpread(id, hash, false)
            container.bookPdfViewPreferences.saveCrop(id, hash, 0, 0)
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                await("actual source page painted") { frame(scenario)?.pageIndex == 0 }
                val before = requireNotNull(frame(scenario))
                openContents()
                await("parsed actual outline appears") {
                    compose.onAllNodesWithText("Chapter two").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("External web").performScrollTo().assertIsNotEnabled()
                capture("outline-ready")
                compose.onNodeWithText("Chapter two").performScrollTo().performClick()
                await("actual direct outline page painted") { frame(scenario)?.pageIndex == 1 }
                await("actual direct outline page persisted") {
                    PdfPosition.decodeResumeOrNull(container.reading.resume(id, hash), 3)?.pageIndex == 1
                }
                compose.onNodeWithText("Atlamadan önceki konuma dön").performScrollTo().performClick()
                await("original page returned through production jump history") { frame(scenario)?.pageIndex == before.pageIndex }
                openContents()
                await("named outline shown") {
                    compose.onAllNodesWithText("Third named").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Third named").performScrollTo().performClick()
                await("named outline destination painted") { frame(scenario)?.pageIndex == 2 }
                await("named outline destination persisted") {
                    PdfPosition.decodeResumeOrNull(container.reading.resume(id, hash), 3)?.pageIndex == 2
                }
                compose.onNodeWithText("Kapat").assertDoesNotExist()
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
                compose.onNodeWithText("Gizle").performClick()
                await("named destination remains painted after hiding reader controls") { frame(scenario)?.pageIndex == 2 }
                capture("outline-destination")
                scenario.recreate()
                await("fresh Activity resumes actual outline page") { frame(scenario)?.pageIndex == 2 }
                // A fragment can retire its source while the Activity is still resumed. That
                // cancellation must release the production modal gate, not leave invisible input blocking.
                var retiredSource: Any? = null
                var priorGeneration = -1L
                scenario.onActivity { activity ->
                    val pdf = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>().single()
                    activity.supportFragmentManager.beginTransaction()
                        .setMaxLifecycle(pdf, androidx.lifecycle.Lifecycle.State.STARTED).commitNow()
                    assertFalse(pdf.isResumed)
                    assertTrue(activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                    retiredSource = activity.javaClass.getDeclaredMethod("getContentsNavigator").apply { isAccessible = true }
                        .invoke(activity)
                    priorGeneration = checkNotNull(retiredSource).javaClass.getDeclaredField("generation")
                        .apply { isAccessible = true }.getLong(retiredSource)
                }
                try {
                    openContents()
                    await("cancelled source releases the actual modal admission gate") {
                        var open = true
                        var attempted = false
                        scenario.onActivity { activity ->
                            open = activity.javaClass.getDeclaredMethod("getContentsOpen").apply { isAccessible = true }
                                .invoke(activity) as Boolean
                            attempted = checkNotNull(retiredSource).javaClass.getDeclaredField("generation")
                                .apply { isAccessible = true }.getLong(retiredSource) > priorGeneration
                        }
                        attempted && !open
                    }
                } finally {
                    scenario.onActivity { activity ->
                        val pdf = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>().single()
                        activity.supportFragmentManager.beginTransaction()
                            .setMaxLifecycle(pdf, androidx.lifecycle.Lifecycle.State.RESUMED).commitNow()
                    }
                }
                await("same reader paints again after source retirement") { frame(scenario)?.pageIndex == 2 }
                openContents()
                await("a fresh explicit open still loads real original contents") {
                    compose.onAllNodesWithText("Chapter two").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Kapat").performClick()
                File(File(checkNotNull(context.getExternalFilesDir(null)), "yaprak-pdf-contents-0.182"),
                    "modal-retirement.json").writeText(org.json.JSONObject()
                        .put("actualFragmentRetiredWhileActivityResumed", true).put("actualModalGateReleased", true)
                        .put("freshExplicitOpenWorked", true).toString(2))
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

    private fun openContents() {
        compose.onNodeWithText("İçindekiler").performScrollTo().performClick()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "yaprak-pdf-contents-0.182")
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
            val point = fragment.drawnPosition.value ?: return@onActivity
            if (fragment.scrolling.value && fragment.bookmarkPage == point.pageIndex) result = point
        }
        return result
    }
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
            "<< /Type /Catalog /Pages 2 0 R /Names << /Dests << /Names [(later) [5 0 R /XYZ 0 750 0]] >> >> /Outlines 13 0 R >>",
            "<< /Type /Pages /Count 3 /Kids [3 0 R 4 0 R 5 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 1000] /Resources << /Font << /F1 6 0 R >> >> /Contents 7 0 R /Annots [10 0 R 11 0 R 12 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 1000] /Resources << /Font << /F1 6 0 R >> >> /Contents 8 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 1000] /Resources << /Font << /F1 6 0 R >> >> /Contents 9 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        ) + streams.map { "<< /Length ${it.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${it}endstream" } + listOf(
            "<< /Type /Annot /Subtype /Link /Rect [20 880 180 920] /A << /S /GoTo /D [4 0 R /XYZ 0 600 1.5] >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [20 780 180 820] /A << /S /GoTo /D (later) >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [20 680 180 720] /A << /S /URI /URI (https://example.invalid) >> >>",
            "<< /Type /Outlines /First 14 0 R /Last 16 0 R /Count 3 >>",
            "<< /Title (Chapter two) /Parent 13 0 R /Dest [4 0 R /Fit] /First 15 0 R /Last 15 0 R /Count 1 /Next 16 0 R >>",
            "<< /Title (Third named) /Parent 14 0 R /Dest (later) >>",
            "<< /Title (External web) /Parent 13 0 R /Prev 14 0 R /A << /S /URI /URI (https://example.invalid) >> >>",
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
