package com.yaprak.reader.reader

import android.graphics.Paint
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomBookPdfViewPreferencesRepository
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

/** Production Activity, actual touch/paint, disk Room and PdfRenderer; only a UUID-owned book is changed. */
@RunWith(AndroidJUnit4::class)
class PdfHorizontalDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var diagnostic: () -> String = { "Reader not open" }

    @Test fun horizontalDragVerticalOverflowPointNavigationAndRecreationUseActualPaintAndDurableMode() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as YaprakApplication).container
        withTimeout(30_000) { container.startup.awaitReady() }
        val id = UUID.randomUUID().toString()
        val bytes = ownedPdf()
        val file = File(context.filesDir, "books/$id.pdf").apply { parentFile!!.mkdirs() }
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
        val book = Book(id, "Owned horizontal PDF", null, BookFormat.PDF, "horizontal.pdf",
            file.name, file.length(), hash, System.currentTimeMillis(), 3)
        var added = false
        try {
            container.library.add(book); added = true
            container.bookPdfViewPreferences.saveFit(id, hash, PdfFitPreference.WIDTH)
            container.bookPdfViewPreferences.saveNavigation(id, hash, PdfNavigationMode.HORIZONTAL_SCROLL)
            container.bookPdfViewPreferences.saveSpread(id, hash, true)
            val initial = PdfResumePosition(1, 600_000)
            container.reading.record(id, hash, ReadingSnapshot(PdfPosition.encode(initial)))
            lateinit var saved: PdfResumePosition
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                diagnostic = { readerWindowDiagnostic(scenario) + "; scroll=" + compose.readerScrollDiagnostic() }
                await("horizontal restored original point") { same(initial, point(scenario)) }
                // Read as a user does: the bottom chrome overlays the default swipeUp start.
                // Hide the real tools before injecting document gestures; keep all paint/point assertions.
                compose.onNodeWithText("Gizle").performClick()
                compose.awaitReaderWindowViewport(scenario, "pdf-horizontal-pages", toolsHidden = true)
                await("settled hidden window retains the original point") { same(initial, point(scenario)) }
                ReaderWindowInput(compose, scenario, "pdf-horizontal-pages").swipeLeft()
                // This is a free-scrolling list: an 80%-width drag need not move the
                // following page past the leading reading line. Continue with real input.
                ReaderWindowInput(compose, scenario, "pdf-horizontal-pages").swipeLeft()
                await("horizontal drag draws the third page") { point(scenario)?.pageIndex == 2 }
                val before = requireNotNull(point(scenario))
                captureReader("horizontal-before-vertical.png")
                ReaderWindowInput(compose, scenario, "pdf-horizontal-pages").swipeUp()
                await("width-fit vertical overflow moves the actual reading line") {
                    point(scenario)?.let { it.pageIndex == 2 && requireNotNull(it.anchorY) > requireNotNull(before.anchorY) + 5_000 } == true
                }
                captureReader("horizontal-after-vertical.png")
                val target = PdfResumePosition(2, 600_000)
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>().single()
                    assertTrue(fragment.goTo(target))
                }
                await("point command settles horizontal and vertical scroll before native draw") { same(target, point(scenario)) }
                saved = requireNotNull(point(scenario))
                await("actual point persisted") { same(saved, PdfPosition.decodeResumeOrNull(container.reading.resume(id, hash), 3)) }
                scenario.recreate()
                await("recreated horizontal reader returns to the durable original point") { same(saved, point(scenario)) }
                container.bookPdfViewPreferences.saveCrop(id, hash, 10, 15)
                await("live crop preserves the horizontal original point after its own layout and paint") {
                    frame(scenario)?.let { same(saved, it.point) && it.geometry.crop == PdfPageCrop(10, 15) } == true
                }
            }
            val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
            try {
                val preferences = RoomBookPdfViewPreferencesRepository(database).observe(id, hash).first()
                assertEquals(PdfNavigationMode.HORIZONTAL_SCROLL, preferences.navigationMode)
                assertEquals(PdfFitPreference.WIDTH, preferences.fitMode); assertTrue(preferences.doublePagePreferred)
                assertEquals(10, preferences.horizontalPercent); assertEquals(15, preferences.verticalPercent)
                assertTrue(same(saved, PdfPosition.decodeResumeOrNull(RoomReadingStateRepository(database).resume(id, hash), 3)))
                assertTrue(database.reading().observed(id, hash).isEmpty())
            } finally { database.close() }
            assertArrayEquals(bytes, withContext(Dispatchers.IO) { file.readBytes() })
        } finally {
            if (added) {
                var result: BookRemovalResult = BookRemovalResult.InUse
                await("owned book lease released") {
                    result = container.remover.remove(id, hash); result != BookRemovalResult.InUse
                }
                assertTrue(result is BookRemovalResult.Removed || result == BookRemovalResult.Missing)
            }
            withContext(Dispatchers.IO) { if (file.exists()) check(file.delete()) }
        }
    }

    private data class PaintedFrame(val point: PdfResumePosition, val geometry: PdfDrawGeometry)

    /** Owned synthetic fixture only; capture the actual screen, including any unexpected overlay. */
    private fun captureReader(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "yaprak-reader-0.178").apply { check(isDirectory || mkdirs()) }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, name).outputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally { bitmap.recycle() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun frame(scenario: ActivityScenario<ReaderActivity>): PaintedFrame? {
        var frame: PaintedFrame? = null
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>().singleOrNull()
                ?: return@onActivity
            val point = fragment.drawnPosition.value ?: return@onActivity
            if (fragment.navigationMode.value != PdfNavigationMode.HORIZONTAL_SCROLL || !fragment.isResumed ||
                fragment.bookmarkPage != point.pageIndex || point.anchorY == null) return@onActivity
            val host = fragment.view as? PdfDrawProofView ?: return@onActivity
            // Observe the exact current native painter; never manufacture tickets, geometry or draw acknowledgements.
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
    private fun point(scenario: ActivityScenario<ReaderActivity>) = frame(scenario)?.point

    private fun same(expected: PdfResumePosition, actual: PdfResumePosition?) =
        actual != null && actual.pageIndex == expected.pageIndex && actual.anchorY != null && expected.anchorY != null &&
            abs(requireNotNull(actual.anchorY) - requireNotNull(expected.anchorY)) <= 3_000

    private suspend fun await(label: String, condition: suspend () -> Boolean) {
        val recent = ArrayDeque<String>()
        try { withTimeout(20_000) {
            while (true) {
                compose.waitForIdle()
                if (condition()) break
                val sample = diagnostic()
                if (recent.lastOrNull() != sample) {
                    recent.addLast(sample)
                    if (recent.size > 12) recent.removeFirst()
                }
                delay(100)
            }
        } }
        catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$label; ${diagnostic()}; recent=${recent.joinToString(" | ")}", timeout)
        }
    }
    private fun ownedPdf(): ByteArray {
        val output = ByteArrayOutputStream()
        val document = android.graphics.pdf.PdfDocument()
        try {
            val paint = Paint().apply { textSize = 12f }
            repeat(3) { index ->
                val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(200, 1600, index + 1).create())
                for (line in 1..50) page.canvas.drawText("Owned page " + (index + 1) + " line " + line, 8f, line * 22f, paint)
                document.finishPage(page)
            }
            document.writeTo(output)
        } finally { document.close() }
        return output.toByteArray()
    }
}
