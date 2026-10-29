package com.yaprak.reader.formats.cbz

import android.app.Application
import android.graphics.*
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.fragment.app.FragmentActivity
import com.yaprak.reader.reader.ReaderSurface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CbzPagePositionTest {
    private fun idleUntil(condition: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue("Actual original page did not settle", condition())
    }
    private fun image(surface: ReaderSurface) = (surface.fragment.requireView() as FrameLayout).getChildAt(0) as CbzPageView
    private fun loaded(surface: ReaderSurface) = surface.fragment.view?.let { root ->
        (root as FrameLayout).getChildAt(1).let { (it as ProgressBar).visibility == View.GONE } && !(surface.fragment as CbzReaderFragment).renderFailed.value
    } == true
    private fun draw(surface: ReaderSurface, width: Int = 900, height: Int = 600): Bitmap {
        val view = image(surface); view.layoutParams = FrameLayout.LayoutParams(width, height)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height); idleUntil { loaded(surface) }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
    private fun attach(activity: FragmentActivity, session: CbzReaderSession, f: CbzSpreadFixture, page: Int = 0): ReaderSurface {
        activity.setContentView(FrameLayout(activity).apply { id = 1002 })
        val surface = session.createSurface(CbzPosition.encode(page, f.document.archive), false, {}, {})
        activity.supportFragmentManager.beginTransaction().replace(1002, surface.fragment).commitNow()
        idleUntil { loaded(surface) }; return surface
    }
    private suspend fun position(surface: ReaderSurface) = surface.bookPositionPercent.first()
    @Test fun savedSpreadInitialDecodePreviewAndAcceptedNavigationWaitForActualPrimaryDraw() = runBlocking {
        CbzSpreadFixture.create().use { f ->
            val session = CbzReaderSession(f.book, f.document, null)
            val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
            try {
                val surface = attach(host.get(), session, f, 1); assertNull(position(surface))
                surface.pageThumbnails!!.render(1); assertNull(position(surface))
                assertTrue(surface.comicSpread!!.restore(true)); assertNull(position(surface))
                val pair = draw(surface)
                try { assertEquals(Color.RED, pair.getPixel(250, 300)); assertEquals(Color.BLUE, pair.getPixel(650, 300)) }
                finally { pair.recycle() }
                assertEquals(40, position(surface)); assertEquals(1, surface.pageNavigator!!.captureStablePage())
                assertEquals("cbz/2.png", surface.bookmarkNavigator!!.capture()!!.location.resourceHref)
                val narrow = draw(surface, 400, 600)
                try { assertEquals(Color.BLUE, narrow.getPixel(200, 300)) } finally { narrow.recycle() }
                assertEquals(40, position(surface)); assertTrue(surface.comicSpread!!.preferred.value)
                assertTrue(surface.pageNavigator!!.goTo(4)); assertNull(position(surface))
                draw(surface, 400, 600).recycle(); assertEquals(100, position(surface))
                assertEquals(4, surface.pageNavigator!!.captureStablePage())
            } finally { host.pause().stop().destroy(); session.close() }
        }
    }
    @Test fun pairUsesExactSelectedMemberAndToggleNeverCountsItsAdjacentOrChangesPosition() = runBlocking {
        CbzSpreadFixture.create().use { f ->
            val session = CbzReaderSession(f.book, f.document, null)
            val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
            try {
                val surface = attach(host.get(), session, f, 3); draw(surface).recycle(); assertEquals(80, position(surface))
                assertTrue(surface.comicSpread!!.apply(true)); assertNull(position(surface))
                draw(surface).recycle(); assertEquals(80, position(surface)); assertEquals(listOf(2, 3), (surface.fragment as CbzReaderFragment).drawnPages.value)
                assertTrue(surface.comicSpread!!.apply(false)); assertNull(position(surface))
                draw(surface).recycle(); assertEquals(80, position(surface))
                assertTrue(surface.pageNavigator!!.goTo(0)); assertNull(position(surface)); draw(surface).recycle(); assertEquals(20, position(surface))
                assertEquals(0, surface.pageNavigator!!.captureStablePage()); assertArrayEquals(f.bytes, f.file.readBytes())
            } finally { host.pause().stop().destroy(); session.close() }
        }
    }
    @Test fun adjacentOnlyClippingAndNativeAlphaCannotGrantSelectedPrimaryPercentage() = runBlocking {
        CbzSpreadFixture.create().use { f ->
            val session = CbzReaderSession(f.book, f.document, null)
            val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
            try {
                val surface = attach(host.get(), session, f, 1); assertTrue(surface.comicSpread!!.restore(true)); draw(surface).recycle()
                assertEquals(40, position(surface)); val root = surface.fragment.requireView() as FrameLayout
                root.clipBounds = Rect(0, 0, 430, 600); draw(surface).recycle(); assertNull(position(surface))
                assertNull(surface.pageNavigator!!.captureStablePage()); root.clipBounds = null
                draw(surface).recycle(); assertEquals(40, position(surface))
                image(surface).alpha = 0f; draw(surface).recycle(); assertNull(position(surface))
                image(surface).alpha = 1f; draw(surface).recycle(); assertEquals(40, position(surface))
            } finally { host.pause().stop().destroy(); session.close() }
        }
    }
    @Test fun pausedReplacedClosedAndFailedSourceRevokePositionUntilFreshActualDraw() = runBlocking {
        CbzSpreadFixture.create().use { f ->
            val session = CbzReaderSession(f.book, f.document, null)
            val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
            try {
                val old = attach(host.get(), session, f, 1); draw(old).recycle(); assertEquals(40, position(old))
                host.pause(); assertNull(position(old)); host.resume(); idleUntil { loaded(old) }; assertNull(position(old))
                draw(old).recycle(); assertEquals(40, position(old))
                val fresh = session.createSurface(CbzPosition.encode(1, f.document.archive), false, {}, {})
                assertNull(position(old)); assertNull(position(fresh))
                host.get().supportFragmentManager.beginTransaction().replace(1002, fresh.fragment).commitNow()
                draw(fresh).recycle(); assertEquals(40, position(fresh))
                f.file.appendBytes(byteArrayOf(0)); assertTrue(fresh.pageNavigator!!.goTo(4)); assertNull(position(fresh))
                idleUntil { (fresh.fragment as CbzReaderFragment).renderFailed.value }; assertNull(position(fresh))
                f.file.writeBytes(f.bytes); assertTrue(fresh.pageNavigator!!.goTo(4)); draw(fresh).recycle(); assertEquals(100, position(fresh))
                session.close(); assertNull(position(fresh)); assertNull(position(old))
            } finally { host.pause().stop().destroy(); session.close() }
        }
    }
}
