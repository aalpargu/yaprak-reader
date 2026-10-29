package com.yaprak.reader.formats

import android.app.Application
import android.graphics.*
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.reader.ReaderSurface
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.*

/** Actual native Fragment/Compose/pixels. The backend seam does not claim physical PDF decoding. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PdfReadingVisibilityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun idleUntil(condition: () -> Boolean) {
        compose.waitForIdle()
        compose.waitUntil(10_000, condition)
        compose.waitForIdle()
    }
    private suspend fun assertHidden(surface: ReaderSurface) {
        assertFalse(surface.readingVisible.first()); assertNull(surface.bookPositionPercent.first())
    }
    private suspend fun assertShown(surface: ReaderSurface, percent: Int) {
        assertTrue(surface.readingVisible.first()); assertEquals(percent, surface.bookPositionPercent.first())
    }
    private fun status(surface: ReaderSurface) = PdfReaderFragment::class.java.getDeclaredField("status")
        .apply { isAccessible = true }.get(surface.fragment) as PdfRenderStatus
    private fun invisibly(surface: ReaderSurface, action: () -> Unit) {
        compose.runOnUiThread {
            val view = surface.fragment.requireView()
            view.alpha = 0f; view.viewTreeObserver.dispatchOnPreDraw()
        }
        action()
    }
    private fun prepared(surface: ReaderSurface, page: Int) {
        invisibly(surface) {
            try { idleUntil { status(surface).renderedPage.value == page } }
            catch (failure: AssertionError) {
                val root = surface.fragment.requireView() as FrameLayout
                throw AssertionError("PDF fixture root=${root.width}x${root.height}, child=${root.getChildAt(0).width}x${root.getChildAt(0).height}, " +
                    "lifecycle=${surface.fragment.lifecycle.currentState}, prepared=${status(surface).renderedPage.value}, failed=${status(surface).renderFailed.value}", failure)
            }
        }
    }
    private fun spreadPrepared(surface: ReaderSurface, enabled: Boolean, page: Int) = invisibly(surface) {
        surface.pdfSpread!!.apply(enabled)
        idleUntil { status(surface).doublePage.value == enabled && status(surface).renderedPage.value == page }
    }
    private fun attach(activity: FragmentActivity, session: PdfReaderSession, page: Int): ReaderSurface {
        lateinit var surface: ReaderSurface
        compose.runOnUiThread {
            activity.setContentView(FrameLayout(activity).apply { id = 1002 })
            surface = session.createSurface(PdfPosition.encode(page), false, {}, {})
            activity.supportFragmentManager.beginTransaction().replace(1002, surface.fragment).commitNow()
            surface.fragment.requireView().alpha = 0f
        }
        prepared(surface, page)
        return surface
    }
    private fun draw(surface: ReaderSurface, reveal: Boolean = true): Bitmap {
        lateinit var image: Bitmap
        compose.runOnUiThread {
            if (reveal) surface.fragment.requireView().alpha = 1f
            val view = surface.fragment.requireActivity().window.decorView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        return image
    }
    private fun save(image: Bitmap, name: String) {
        val file = java.io.File(System.getProperty("yaprak.screenshotDir"), "pdf-position-0.129.0/$name")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun native(failBlue: () -> Boolean = { false }) = PdfNativeFixture(width = 80, height = 120).apply {
        onRender = { bitmap ->
            val color = bitmap.getPixel(0, 0)
            if (color == Color.BLUE && failBlue()) throw java.io.IOException("native page failed")
            bitmap.eraseColor(color)
        }
    }
    private fun session(native: PdfNativeFixture) = PdfReaderSession(
        Book("visible-pdf", "PDF visible", null, BookFormat.PDF, "visible.pdf", "visible.pdf", 1, "a".repeat(64), 1, 2),
        PdfDocument(native), null)

    @Test fun spreadWaitsForPreparationAndRealPrimaryPixelsWithoutNormalizingTheAnchor() = runBlocking {
        val native = native(); val session = session(native)
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible(); val activity = host.get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val surface = attach(activity, session, 0)
            assertHidden(surface); assertNull(surface.pageLabel.first())
            assertNull(surface.pageNavigator!!.currentPage); assertNull(surface.bookmarkNavigator!!.capture())
            val snapshots = CopyOnWriteArrayList<ReadingSnapshot>()
            scope.launch { surface.observe({ true }) { snapshots += it } }
            spreadPrepared(surface, true, 0)
            assertHidden(surface); assertTrue(snapshots.isEmpty())
            draw(surface).let { image ->
                try {
                    assertEquals(Color.RED, image.getPixel(250, 300)); assertEquals(Color.BLUE, image.getPixel(650, 300))
                    save(image, "spread.png")
                }
                finally { image.recycle() }
            }
            assertShown(surface, 50); assertEquals("1–2 / 2", surface.pageLabel.first())
            assertEquals(0, surface.pageNavigator!!.captureStablePage())
            assertEquals(PdfPosition.encode(0), surface.bookmarkNavigator!!.capture()!!.location)
            assertEquals(listOf(PdfPosition.encode(0)), snapshots.map { it.location })
            assertFalse(surface.forward())
            spreadPrepared(surface, false, 0)
            assertHidden(surface); assertNull(surface.pageLabel.first())
            draw(surface).recycle(); assertEquals("1 / 2", surface.pageLabel.first())
            assertTrue(surface.forward()); prepared(surface, 1)
            assertHidden(surface); assertNull(surface.pageNavigator!!.currentPage)
            draw(surface).let { image -> try { assertEquals(Color.BLUE, image.getPixel(450, 300)) } finally { image.recycle() } }
            assertShown(surface, 100); assertEquals(1, surface.pageNavigator!!.captureStablePage()); assertEquals(PdfPosition.encode(1), snapshots.last().location)
        } finally { scope.cancel(); host.pause().stop().destroy(); session.close() }
    }

    @Test fun requestedPreparedAndThumbnailPagesDoNotEnableVisibilityUntilTheirOwnNativeDraw() = runBlocking {
        val session = session(native()); val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible(); val activity = host.get()
        try {
            val surface = attach(activity, session, 0)
            surface.pdfThumbnails!!.render(1)
            assertHidden(surface); assertNull(surface.pageLabel.first())
            assertTrue(surface.pageNavigator!!.goTo(1)); prepared(surface, 1)
            assertHidden(surface); assertNull(surface.pageNavigator!!.captureStablePage())
            draw(surface).recycle(); assertShown(surface, 100)
            assertEquals("2 / 2", surface.pageLabel.first())
            assertTrue(surface.pageNavigator!!.goTo(1)); assertShown(surface, 100)
            assertFalse(surface.pageNavigator!!.goTo(2)); assertEquals(1, surface.pageNavigator!!.currentPage)
        } finally { host.pause().stop().destroy(); session.close() }
    }

    @Test fun failedNeighborAndPendingNavigationNeverSaveAnOldOrAdjacentPage() = runBlocking {
        var failBlue = true
        val native = native { failBlue }; val session = session(native)
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible(); val activity = host.get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val surface = attach(activity, session, 0)
            val snapshots = CopyOnWriteArrayList<ReadingSnapshot>()
            scope.launch { surface.observe({ true }) { snapshots += it } }
            surface.pdfSpread!!.apply(true); idleUntil { status(surface).renderFailed.value }
            draw(surface).recycle()
            assertHidden(surface); assertNull(surface.pageLabel.first())
            assertNull(surface.pageNavigator!!.captureStablePage()); assertTrue(snapshots.isEmpty())
            failBlue = false
            assertTrue(surface.pageNavigator!!.goTo(1)); prepared(surface, 1)
            assertHidden(surface); assertTrue(snapshots.isEmpty())
            draw(surface).recycle(); assertShown(surface, 100)
            assertFalse(surface.renderingFailed.first()); assertEquals(listOf(PdfPosition.encode(1)), snapshots.map { it.location })
        } finally { scope.cancel(); host.pause().stop().destroy(); session.close() }
    }

    @Test fun pauseResumeReplacementAndCloseRequireNewDrawAndRevokeTheOldSurface() = runBlocking {
        val session = session(native()); val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible(); val activity = host.get()
        try {
            val old = attach(activity, session, 0); draw(old).recycle(); assertShown(old, 50)
            host.pause(); assertHidden(old); assertNull(old.pageNavigator!!.captureStablePage())
            compose.runOnUiThread { old.fragment.requireView().alpha = 0f }; host.resume().visible(); assertHidden(old)
            draw(old).recycle(); assertShown(old, 50)
            val fresh = session.createSurface(PdfPosition.encode(1), false, {}, {})
            assertHidden(old); assertNull(old.pageLabel.first()); assertNull(old.pageNavigator!!.currentPage)
            assertFalse(old.pageNavigator!!.goTo(1)); assertFalse(old.forward()); assertFalse(old.backward())
            assertHidden(fresh)
            activity.supportFragmentManager.beginTransaction().replace(1002, fresh.fragment).commitNow()
            compose.runOnUiThread { fresh.fragment.requireView().alpha = 0f }; prepared(fresh, 1); assertHidden(fresh)
            draw(fresh).recycle(); assertShown(fresh, 100); assertEquals("2 / 2", fresh.pageLabel.first())
            session.close(); assertHidden(fresh); assertHidden(old)
            assertFalse(fresh.pageNavigator!!.goTo(0)); assertNull(fresh.pageNavigator!!.currentPage)
        } finally { host.pause().stop().destroy(); session.close() }
    }

    @Test fun nativeClippingAlphaAndGeometryChangesNeedVisiblePrimaryRedrawWhilePreservingOriginalColors() = runBlocking {
        val native = native(); val session = session(native)
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible(); val activity = host.get()
        try {
            val surface = attach(activity, session, 0)
            spreadPrepared(surface, true, 0)
            draw(surface).recycle(); assertShown(surface, 50)
            val view = surface.fragment.requireView()
            (view.parent as View).clipBounds = Rect(450, 0, 900, 600)
            view.viewTreeObserver.dispatchOnPreDraw()
            assertHidden(surface); assertNull(surface.pageNavigator!!.captureStablePage())
            draw(surface).let { image ->
                try {
                    assertNotEquals(Color.RED, image.getPixel(250, 300)); assertEquals(Color.BLUE, image.getPixel(650, 300))
                    save(image, "adjacent-only.png")
                } finally { image.recycle() }
            }
            assertHidden(surface)
            (view.parent as View).clipBounds = null
            assertHidden(surface); draw(surface).recycle(); assertShown(surface, 50)
            view.alpha = 0f; view.viewTreeObserver.dispatchOnPreDraw(); draw(surface, reveal = false).recycle()
            assertHidden(surface); assertNull(surface.pageLabel.first())
            view.alpha = 1f; draw(surface).recycle(); assertShown(surface, 50)
            compose.runOnUiThread { view.visibility = View.INVISIBLE }
            assertHidden(surface); assertNull(surface.pageNavigator!!.captureStablePage())
            compose.runOnUiThread { view.visibility = View.VISIBLE }
            assertHidden(surface); draw(surface).recycle(); assertShown(surface, 50)
            val requests = native.events.count { it.startsWith("render:") }
            invisibly(surface) {
                surface.pdfView!!.fitWidth(); surface.pdfCrop!!.apply(PdfPageCrop(10, 10))
                compose.waitForIdle()
            }
            assertHidden(surface)
            draw(surface).let { image -> try { save(image, "cropped-width.png") } finally { image.recycle() } }
            assertShown(surface, 50)
            assertEquals(0, surface.pageNavigator!!.currentPage)
            assertEquals(requests, native.events.count { it.startsWith("render:") })
            assertTrue(native.bitmaps.all { !it.isRecycled && it.getPixel(0, 0) in listOf(Color.RED, Color.BLUE) })
        } finally { host.pause().stop().destroy(); session.close() }
    }

    @Test fun openingTheActualLastPageShowsItsPositionWithoutInventingEarlierCoverage() = runBlocking {
        val session = session(native()); val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val surface = attach(host.get(), session, 1)
            val snapshots = CopyOnWriteArrayList<ReadingSnapshot>()
            scope.launch { surface.observe({ true }) { snapshots += it } }
            assertHidden(surface); assertTrue(snapshots.isEmpty())
            surface.pdfThumbnails!!.render(0)
            assertHidden(surface); assertTrue(snapshots.isEmpty())
            draw(surface).let { image -> try { assertEquals(Color.BLUE, image.getPixel(450, 300)) } finally { image.recycle() } }
            assertShown(surface, 100); assertEquals("2 / 2", surface.pageLabel.first())
            assertEquals(listOf(PdfPosition.encode(1)), snapshots.map { it.location })
            assertTrue(snapshots.all { it.spans.isEmpty() })
            assertTrue(surface.backward()); prepared(surface, 0)
            assertHidden(surface); assertEquals(listOf(PdfPosition.encode(1)), snapshots.map { it.location })
            draw(surface).recycle(); assertShown(surface, 50)
            assertEquals(listOf(PdfPosition.encode(1), PdfPosition.encode(0)), snapshots.map { it.location })
            assertTrue(snapshots.all { it.spans.isEmpty() })
        } finally { scope.cancel(); host.pause().stop().destroy(); session.close() }
    }
}
