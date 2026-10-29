package com.yaprak.reader.formats.cbz

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.fragment.app.FragmentActivity
import com.yaprak.reader.reader.ReaderSurface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CbzSpreadReaderTest {
    private fun idleUntil(condition: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue("Native spread did not settle", condition())
    }
    private fun image(surface: ReaderSurface) = (surface.fragment.requireView() as FrameLayout).getChildAt(0) as CbzPageView
    private fun loaded(surface: ReaderSurface) = surface.fragment.view?.let { root ->
        (root as FrameLayout).getChildAt(1).let { (it as ProgressBar).visibility == View.GONE } && !(surface.fragment as CbzReaderFragment).renderFailed.value
    } == true
    private fun draw(surface: ReaderSurface, width: Int = 900, height: Int = 600): Bitmap {
        val view = image(surface)
        // Keep the actual child viewport at this size through subsequent native parent traversals.
        view.layoutParams = FrameLayout.LayoutParams(width, height)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        idleUntil { loaded(surface) }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap)); return bitmap
    }
    private fun attach(activity: FragmentActivity, session: CbzReaderSession, initial: Int? = null): ReaderSurface {
        val root = FrameLayout(activity).apply { id = 1002 }; activity.setContentView(root)
        val surface = session.createSurface(initial?.let { CbzPosition.encode(it, sessionArchive(session)) }, false, {}, {})
        activity.supportFragmentManager.beginTransaction().replace(1002, surface.fragment).commitNow()
        idleUntil { loaded(surface) }; return surface
    }
    private fun sessionArchive(session: CbzReaderSession): CbzArchive = session.javaClass.getDeclaredField("archive").apply { isAccessible = true }.get(session) as CbzArchive
    private fun label(surface: ReaderSurface) = runBlocking { surface.pageLabel.first() }
    @Test fun nativePairUsesBothOriginalPixelsAndKeepsTheSelectedRightPageAnchorAcrossWidth() = runBlocking {
        CbzSpreadFixture.create().use { f ->
            val session = CbzReaderSession(f.book, f.document, null)
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
            try {
                val surface = attach(controller.get(), session, 1)
                assertNull(surface.pageNavigator!!.captureStablePage()); assertFalse(surface.comicSpread!!.apply(true))
                draw(surface).recycle(); assertEquals(1, surface.pageNavigator!!.captureStablePage())
                assertTrue(surface.comicSpread!!.apply(true)); assertNull(surface.pageNavigator!!.captureStablePage())
                assertFalse(surface.readingVisible.first()); assertNull(surface.bookmarkNavigator!!.capture())
                val pair = draw(surface)
                assertEquals(Color.RED, pair.getPixel(250, 300)); assertEquals(Color.BLUE, pair.getPixel(650, 300))
                val file = java.io.File(System.getProperty("yaprak.screenshotDir"), "cbz-spread-originals-wide.png")
                file.parentFile!!.mkdirs(); file.outputStream().use { pair.compress(Bitmap.CompressFormat.PNG, 100, it) }; pair.recycle()
                assertEquals(1, surface.pageNavigator!!.captureStablePage()); assertEquals("1–2 / 5", label(surface))
                assertEquals("cbz/2.png", surface.bookmarkNavigator!!.capture()!!.location.resourceHref)
                assertTrue(image(surface).contentDescription.toString().contains("Odak sayfa 2"))
                val narrow = draw(surface, 400, 600)
                assertEquals(Color.BLUE, narrow.getPixel(200, 300)); narrow.recycle()
                assertEquals(1, surface.pageNavigator!!.captureStablePage()); assertEquals("2 / 5", label(surface))
                assertTrue(surface.comicSpread!!.preferred.value)
                draw(surface).recycle(); assertEquals(1, surface.pageNavigator!!.captureStablePage()); assertEquals("1–2 / 5", label(surface))
            } finally { controller.pause().stop().destroy(); session.close() }
        }
    }
    @Test fun pairTurnsSkipOnlyDisplayedMembersAndOddLastPageStillOpensWithoutInventingAnAdjacent() = runBlocking {
        CbzSpreadFixture.create().use { f ->
            val session = CbzReaderSession(f.book, f.document, null)
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
            try {
                val surface = attach(controller.get(), session)
                draw(surface).recycle(); assertTrue(surface.comicSpread!!.apply(true)); draw(surface).recycle()
                assertFalse(surface.backward()); assertTrue(surface.forward()); assertNull(surface.pageNavigator!!.captureStablePage())
                draw(surface).useBitmap { assertEquals(Color.GREEN, it.getPixel(250, 300)); assertEquals(Color.YELLOW, it.getPixel(650, 300)) }
                assertEquals(2, surface.pageNavigator!!.captureStablePage()); assertEquals("3–4 / 5", label(surface))
                assertTrue(surface.forward()); draw(surface).useBitmap { assertEquals(Color.MAGENTA, it.getPixel(450, 300)) }
                assertEquals(4, surface.pageNavigator!!.captureStablePage()); assertEquals("5 / 5", label(surface)); assertFalse(surface.forward())
                assertTrue(surface.backward()); draw(surface).recycle(); assertEquals(2, surface.pageNavigator!!.captureStablePage())
                draw(surface, 400, 600).recycle(); assertTrue(surface.forward()); draw(surface, 400, 600).recycle()
                assertEquals(3, surface.pageNavigator!!.captureStablePage()); assertEquals("4 / 5", label(surface))
                draw(surface).recycle(); assertEquals(3, surface.pageNavigator!!.captureStablePage()); assertEquals("3–4 / 5", label(surface))
            } finally { controller.pause().stop().destroy(); session.close() }
        }
    }
    @Test fun pausedReplacedAndClosedSurfacesCannotToggleButRetainedSessionRecreatesItsChoice() = runBlocking {
        CbzSpreadFixture.create().use { f ->
            val session = CbzReaderSession(f.book, f.document, null)
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
            try {
                val old = attach(controller.get(), session, 1)
                draw(old).recycle(); assertTrue(old.comicSpread!!.apply(true)); draw(old).recycle()
                controller.pause(); assertFalse(old.comicSpread!!.apply(false)); assertTrue(old.comicSpread!!.preferred.value)
                assertNull(old.bookmarkNavigator!!.capture()); controller.resume(); idleUntil { loaded(old) }; draw(old).recycle()
                val fresh = session.createSurface(CbzPosition.encode(1, f.document.archive), false, {}, {})
                assertTrue(fresh.comicSpread!!.preferred.value); assertFalse(old.comicSpread!!.apply(false))
                assertFalse(old.forward()); assertFalse(old.readingVisible.first())
                controller.get().supportFragmentManager.beginTransaction().replace(1002, fresh.fragment).commitNow()
                draw(fresh).recycle(); assertEquals(1, fresh.pageNavigator!!.captureStablePage()); assertEquals("1–2 / 5", label(fresh))
                assertTrue(fresh.comicSpread!!.apply(false)); assertFalse(old.comicSpread!!.apply(true)); draw(fresh).recycle()
                assertEquals(1, fresh.pageNavigator!!.captureStablePage()); assertEquals("2 / 5", label(fresh))
                session.close(); assertFalse(fresh.comicSpread!!.apply(true)); assertFalse(fresh.readingVisible.first())
            } finally { controller.pause().stop().destroy(); session.close() }
        }
    }
    @Test fun changedOriginalOnSpreadRequestHasNoNativeProofAndExactSourceCanRetry() = runBlocking {
        CbzSpreadFixture.create().use { f ->
            val session = CbzReaderSession(f.book, f.document, null)
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
            try {
                val surface = attach(controller.get(), session); draw(surface).recycle()
                f.file.appendBytes(byteArrayOf(0)); assertTrue(surface.comicSpread!!.apply(true))
                idleUntil { (surface.fragment as CbzReaderFragment).renderFailed.value }
                assertNull(surface.pageNavigator!!.captureStablePage()); assertFalse(surface.readingVisible.first())
                assertFalse(surface.forward()); assertFalse(surface.comicSpread!!.apply(false))
                f.file.writeBytes(f.bytes)
                assertTrue(surface.pageNavigator!!.goTo(0)); draw(surface).recycle()
                assertTrue(surface.readingVisible.first()); assertEquals("1–2 / 5", label(surface))
            } finally { controller.pause().stop().destroy(); session.close() }
        }
    }
    private inline fun Bitmap.useBitmap(action: (Bitmap) -> Unit) { try { action(this) } finally { recycle() } }
}
