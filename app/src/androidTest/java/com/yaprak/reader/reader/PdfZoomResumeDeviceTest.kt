package com.yaprak.reader.reader

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomReadingStateRepository
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookRemovalResult
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.settings.PdfFitPreference
import com.yaprak.reader.domain.settings.PdfNavigationMode
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.formats.pdf.PdfDrawGeometry
import com.yaprak.reader.formats.pdf.PdfDrawProofView
import com.yaprak.reader.formats.pdf.PdfPageDrawWitness
import com.yaprak.reader.formats.pdf.PdfPosition
import com.yaprak.reader.formats.pdf.PdfReaderFragment
import com.yaprak.reader.formats.pdf.PdfResumePosition
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real PdfRenderer, app graph and disk Room. No simulated native paint or direct status mutation. */
@RunWith(AndroidJUnit4::class)
class PdfZoomResumeDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var diagnostic: () -> String = { "Reader not open" }

    @Test fun nativeZoomPointPersistsThroughReaderRecreationAndCrop() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as YaprakApplication).container
        withTimeout(30_000) { container.startup.awaitReady() }
        val id = UUID.randomUUID().toString()
        val original = tallPdf()
        val file = File(context.filesDir, "books/$id.pdf").apply { parentFile!!.mkdirs() }
        withContext(Dispatchers.IO) { file.writeBytes(original) }
        val hash = MessageDigest.getInstance("SHA-256").digest(original)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        val book = Book(id, "PDF zoom resume device", null, BookFormat.PDF, "zoom-resume.pdf",
            file.name, file.length(), hash, System.currentTimeMillis(), 3)
        var added = false
        try {
            container.library.add(book)
            added = true
            container.bookPdfViewPreferences.saveFit(id, hash, PdfFitPreference.WIDTH)
            container.bookPdfViewPreferences.saveNavigation(id, hash, PdfNavigationMode.VERTICAL_SCROLL)
            container.bookPdfViewPreferences.saveSpread(id, hash, false)
            container.bookPdfViewPreferences.saveCrop(id, hash, 0, 0)
            val seed = PdfResumePosition(1, 600_000)
            container.reading.record(id, hash, ReadingSnapshot(PdfPosition.encode(seed)))

            lateinit var moved: PdfResumePosition
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                diagnostic = { readerWindowDiagnostic(scenario) }
                await("initial original point and width fit") {
                    frame(scenario)?.let { samePoint(seed, it.point) && it.geometry.zoom == 1f } == true
                }
                compose.onNodeWithText("Gizle").performClick()
                compose.awaitReaderWindowViewport(scenario, "pdf-continuous-pages", toolsHidden = true)
                await("hidden tools and resized viewport keep the initial original point") {
                    frame(scenario)?.let { samePoint(seed, it.point) && it.geometry.zoom == 1f } == true
                }
                val before = requireNotNull(frame(scenario)).point
                ReaderWindowInput(compose, scenario, "pdf-continuous-pages").doubleTap()
                await("actual double-tap zoom paint") { frame(scenario)?.geometry?.zoom == 2f }
                ReaderWindowInput(compose, scenario, "pdf-continuous-pages").pan()
                await("actual panned original-point paint") {
                    frame(scenario)?.let {
                        it.geometry.zoom == 2f && it.geometry.pan != Offset.Zero &&
                            it.point.pageIndex == 1 &&
                            abs(requireNotNull(it.point.anchorY) - requireNotNull(before.anchorY)) > 5_000
                    } == true
                }
                moved = requireNotNull(frame(scenario)).point
                await("zoomed native point persisted by ReaderViewModel") {
                    samePoint(moved, PdfPosition.decodeResumeOrNull(container.reading.resume(id, hash), 3))
                }
                ReaderWindowInput(compose, scenario, "pdf-continuous-pages").tap()
                compose.awaitReaderWindowViewport(scenario, "pdf-continuous-pages", toolsHidden = false)
                await("showing tools keeps the panned original point after viewport placement") {
                    frame(scenario)?.let { samePoint(moved, it.point) && baseline(it.geometry) } == true
                }
                compose.onNodeWithText("Yer imleri").performClick()
                compose.onNodeWithText("Bu konumu kaydet").performClick()
                await("original-point bookmark from the actual reader") {
                    val bookmark = container.bookmarks.observe(id, hash).first().singleOrNull()
                    samePoint(moved, PdfPosition.decodeResumeOrNull(bookmark?.target?.location, 3))
                }
                compose.onNodeWithText("Kapat").performClick()

                scenario.recreate()
                await("Activity recreation returns to original Y at baseline zoom") {
                    frame(scenario)?.let { samePoint(moved, it.point) && baseline(it.geometry) } == true
                }
                // Actual preferences flow applies the crop to the live production reader.
                container.bookPdfViewPreferences.saveCrop(id, hash, 10, 15)
                await("live crop keeps original Y after fresh native paint") {
                    frame(scenario)?.let {
                        samePoint(moved, it.point) && baseline(it.geometry) &&
                            it.geometry.crop == PdfPageCrop(10, 15)
                    } == true
                }
                await("cropped reading point persisted") {
                    samePoint(moved, PdfPosition.decodeResumeOrNull(container.reading.resume(id, hash), 3))
                }
            }

            // A separate disk connection proves the durable row, without closing the app-owned DB.
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
            try {
                val saved = RoomReadingStateRepository(db).resume(id, hash)
                assertTrue("Independent Room connection must read the zoomed original Y",
                    samePoint(moved, PdfPosition.decodeResumeOrNull(saved, 3)))
                assertTrue("PDF paint must not create EPUB observed-text spans", db.reading().observed(id, hash).isEmpty())
            } finally { db.close() }
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                await("fresh reader uses durable original Y and cropped preferences") {
                    frame(scenario)?.let {
                        samePoint(moved, it.point) && baseline(it.geometry) &&
                            it.geometry.crop == PdfPageCrop(10, 15)
                    } == true
                }
            }
            assertArrayEquals("The original PDF must remain unchanged", original,
                withContext(Dispatchers.IO) { file.readBytes() })
        } finally {
            if (added) {
                var removal: BookRemovalResult = BookRemovalResult.InUse
                await("owned test book lease released for cleanup") {
                    removal = container.remover.remove(id, hash)
                    removal != BookRemovalResult.InUse
                }
                assertTrue("Only the UUID-owned test book is removed",
                    removal is BookRemovalResult.Removed || removal == BookRemovalResult.Missing)
            }
            // This UUID file belongs exclusively to this test; no library-wide cleanup or app reset.
            withContext(Dispatchers.IO) { if (file.exists()) check(file.delete()) }
        }
    }

    private data class PaintedFrame(val point: PdfResumePosition, val geometry: PdfDrawGeometry)

    @Suppress("UNCHECKED_CAST")
    private fun frame(scenario: ActivityScenario<ReaderActivity>): PaintedFrame? {
        var frame: PaintedFrame? = null
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>()
                .singleOrNull() ?: return@onActivity
            val point = fragment.drawnPosition.value ?: return@onActivity
            if (!fragment.scrolling.value || fragment.bookmarkPage != point.pageIndex || point.anchorY == null)
                return@onActivity
            val host = fragment.view as? PdfDrawProofView ?: return@onActivity
            // Read the exact painter's geometry; never forge a ticket, paint or visible state.
            val witnesses = PdfDrawProofView::class.java.getDeclaredField("witnesses")
                .apply { isAccessible = true }.get(host) as Map<Int, PdfPageDrawWitness>
            val witness = witnesses[point.pageIndex] ?: return@onActivity
            if (witness.paintedTicket == null) return@onActivity
            val geometry = PdfPageDrawWitness::class.java.getDeclaredField("geometry")
                .apply { isAccessible = true }.get(witness) as? PdfDrawGeometry ?: return@onActivity
            frame = PaintedFrame(point, geometry)
        }
        return frame
    }

    private fun baseline(geometry: PdfDrawGeometry) = geometry.zoom == 1f && geometry.pan == Offset.Zero

    private fun samePoint(expected: PdfResumePosition, actual: PdfResumePosition?): Boolean =
        actual != null && expected.pageIndex == actual.pageIndex && expected.anchorY != null &&
            actual.anchorY != null && abs(expected.anchorY - actual.anchorY) <= 3_000

    private suspend fun await(reason: String, condition: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        val recent = ArrayDeque<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            if (condition()) return
            val sample = diagnostic()
            if (recent.lastOrNull() != sample) {
                recent.addLast(sample)
                if (recent.size > 12) recent.removeFirst()
            }
            delay(100)
        }
        fail("PDF device test timed out: $reason; ${diagnostic()}; recent=${recent.joinToString(" | ")}")
    }

    private fun tallPdf(): ByteArray {
        val streams = (1..3).map { page ->
            "0 0 0 RG 2 w 10 1080 m 190 1080 l S 10 960 m 190 960 l S 10 480 m 190 480 l S\n" +
                "BT /F1 12 Tf 20 500 Td (Original page $page) Tj ET\n"
        }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Count 3 /Kids [3 0 R 4 0 R 5 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 1200] /Resources << /Font << /F1 6 0 R >> >> /Contents 7 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 1200] /Resources << /Font << /F1 6 0 R >> >> /Contents 8 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 1200] /Resources << /Font << /F1 6 0 R >> >> /Contents 9 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        ) + streams.map { "<< /Length ${it.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${it}endstream" }
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
