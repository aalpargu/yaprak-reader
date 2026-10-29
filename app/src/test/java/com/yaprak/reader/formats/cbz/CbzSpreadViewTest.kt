package com.yaprak.reader.formats.cbz

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CbzSpreadViewTest {
    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun picture(color: Int) = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun pair(anchor: Int) = CbzFrame(anchor, listOf(CbzRenderedPage(0, picture(Color.RED)), CbzRenderedPage(1, picture(Color.BLUE))))
    private fun draw(view: CbzPageView): Bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    @Test fun visibleAdjacentPageCannotGrantInvisiblePrimaryAndClearingRecyclesBothMembers() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
        var active = true; var proof: Int? = null
        val root = FrameLayout(controller.get()); val view = CbzPageView(controller.get(), { active })
        root.addView(view, FrameLayout.LayoutParams(900, 600)); controller.get().setContentView(root)
        val frame = pair(1)
        try {
            layout(root, 450, 600); layout(view, 900, 600); view.frameDrawn = { proof = it }; view.install(frame, "pair")
            draw(view).recycle(); assertNull(proof) // Left original visible; primary right original fully clipped.
            layout(root, 900, 600); layout(view, 900, 600)
            draw(view).also { assertEquals(Color.RED, it.getPixel(250, 300)); assertEquals(Color.BLUE, it.getPixel(650, 300)); it.recycle() }
            assertEquals(1, proof)
            root.alpha = 0f; draw(view).recycle(); assertNull(proof)
            root.alpha = 1f; active = false; draw(view).recycle(); assertNull(proof)
            active = true; draw(view).recycle(); assertEquals(1, proof)
            root.removeView(view); assertNull(proof); draw(view).recycle(); assertNull(proof)
            view.clear(); view.clear(); assertTrue(frame.pages.all { it.bitmap.isRecycled })
        } finally { view.clear(); controller.pause().stop().destroy() }
    }
    @Test fun zoomedPairOnlyGrantsPrimaryWhenItsActualImageIntersectsTheWindow() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
        var proof: Int? = null
        val root = FrameLayout(controller.get()); val view = CbzPageView(controller.get(), { true })
        root.addView(view, FrameLayout.LayoutParams(900, 600)); controller.get().setContentView(root)
        try {
            layout(root, 900, 600); layout(view, 900, 600); view.frameDrawn = { proof = it }; view.install(pair(1), "pair")
            draw(view).recycle(); assertEquals(1, proof)
            view.javaClass.getDeclaredField("zoom").apply { isAccessible = true }.setFloat(view, 3f)
            view.javaClass.getDeclaredField("panX").apply { isAccessible = true }.setFloat(view, 750f)
            draw(view).also { assertEquals(Color.RED, it.getPixel(450, 300)); it.recycle() }; assertNull(proof)
            view.javaClass.getDeclaredField("panX").apply { isAccessible = true }.setFloat(view, -750f)
            draw(view).also { assertEquals(Color.BLUE, it.getPixel(450, 300)); it.recycle() }; assertEquals(1, proof)
            layout(view, 850, 600); assertNull(proof)
        } finally { view.clear(); controller.pause().stop().destroy() }
    }
    @Test fun actualViewportThresholdAndOddPairsKeepExactPrimaryIndices() {
        assertFalse(CbzSpreadLayout.wide(599, 400, 1f)); assertTrue(CbzSpreadLayout.wide(600, 400, 1f))
        assertFalse(CbzSpreadLayout.wide(900, 1200, 1f)); assertFalse(CbzSpreadLayout.wide(900, 600, 2f))
        assertFalse(CbzSpreadLayout.wide(900, 600, Float.NaN)); assertFalse(CbzSpreadLayout.wide(900, 600, 0f))
        assertEquals(listOf(0, 1), CbzSpreadLayout.pages(1, 5, true)); assertEquals(listOf(1), CbzSpreadLayout.pages(1, 5, false))
        assertEquals(listOf(4), CbzSpreadLayout.pages(4, 5, true)); assertEquals(listOf(0), CbzSpreadLayout.pages(0, 1, true))
        assertEquals(2, CbzSpreadLayout.turn(1, 5, true, 1)); assertEquals(2, CbzSpreadLayout.turn(4, 5, true, -1))
        assertEquals(2, CbzSpreadLayout.turn(1, 5, false, 1)); assertNull(CbzSpreadLayout.turn(0, 5, true, -1))
        assertNull(CbzSpreadLayout.turn(4, 5, true, 1)); assertNull(CbzSpreadLayout.turn(1, 5, true, 2))
    }
}
