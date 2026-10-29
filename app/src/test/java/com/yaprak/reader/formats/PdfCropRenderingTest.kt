package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real native Compose pixels/touch; fixture bitmaps do not claim native PDF decoding acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfCropRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `symmetric crop removes all source edge colors and original restores them without raster or progress writes`() {
        val source = edgedBitmap()
        val original = pixels(source)
        val fixture = show(source); waitForPage(fixture, 0)
        assertEquals(Color.RED, sample(fixture, 40f, 120f))
        assertEquals(Color.GREEN, sample(fixture, 200f, 120f))
        assertEquals(Color.BLUE, sample(fixture, 120f, 10f))
        assertEquals(Color.YELLOW, sample(fixture, 120f, 230f))
        val requests = fixture.requests.toList(); val completions = fixture.completions.toList()
        val snapshots = fixture.pages.toList(); val ticket = fixture.status.renderedPage.value
        apply(fixture, PdfPageCrop(10, 10))
        assertEquals(Color.WHITE, sample(fixture, 40f, 120f))
        assertEquals(Color.WHITE, sample(fixture, 200f, 120f))
        assertEquals(Color.WHITE, sample(fixture, 120f, 10f))
        assertEquals(Color.WHITE, sample(fixture, 120f, 230f))
        apply(fixture, PdfPageCrop())
        assertEquals(Color.RED, sample(fixture, 40f, 120f))
        assertEquals(Color.BLUE, sample(fixture, 120f, 10f))
        assertEquals(requests, fixture.requests.toList()); assertEquals(completions, fixture.completions.toList())
        assertEquals(snapshots, fixture.pages.toList()); assertEquals(ticket, fixture.status.renderedPage.value)
        assertEquals(0, fixture.status.requestedPage.value); assertFalse(fixture.status.renderFailed.value)
        assertArrayEquals(original, pixels(source))
    }

    @Test fun `crop retains page and width fit modes and night filtering treats only the selected source`() {
        val source = edgedBitmap(); val original = pixels(source)
        val fixture = show(source); waitForPage(fixture, 0)
        apply(fixture, PdfPageCrop(10, 10))
        val requests = fixture.requests.toList(); val snapshots = fixture.pages.toList()
        assertEquals(BACKGROUND, sample(fixture, 10f, 120f))
        fit(fixture, PdfFitMode.WIDTH)
        assertEquals(Color.WHITE, sample(fixture, 10f, 120f))
        compose.runOnIdle { fixture.night.value = true }
        assertEquals(Color.BLACK, sample(fixture, 10f, 120f))
        fit(fixture, PdfFitMode.PAGE)
        assertEquals(BACKGROUND, sample(fixture, 10f, 120f))
        assertEquals(Color.BLACK, sample(fixture, 120f, 10f))
        compose.runOnIdle { fixture.night.value = false }
        assertEquals(Color.WHITE, sample(fixture, 120f, 10f))
        assertEquals(PdfPageCrop(10, 10), fixture.crop.value.crop)
        assertEquals(requests, fixture.requests.toList()); assertEquals(snapshots, fixture.pages.toList())
        assertArrayEquals(original, pixels(source))
    }

    @Test fun `reapplying the same crop resets real zoom and pan while preserving its fit and page`() {
        val fixture = show(edgedBitmap()); waitForPage(fixture, 0)
        apply(fixture, PdfPageCrop(10, 10))
        val baseline = captureWindow()
        try {
            compose.onNodeWithTag(VIEWPORT).performTouchInput { doubleClick(center) }
            val zoomed = captureWindow()
            try {
                assertFalse("Real double tap must zoom", baseline.sameAs(zoomed))
                compose.onNodeWithTag(VIEWPORT).performTouchInput {
                    val first = center + Offset(-24f, -12f); val second = center + Offset(24f, 12f)
                    down(0, first); down(1, second)
                    moveTo(0, first + Offset(20f, 15f)); moveTo(1, second + Offset(20f, 15f))
                    moveTo(0, first + Offset(50f, 35f)); moveTo(1, second + Offset(50f, 35f))
                    up(0); up(1)
                }
                captureWindow().useBitmap { assertFalse("Real transform gesture must pan", zoomed.sameAs(it)) }
            } finally { zoomed.recycle() }
            apply(fixture, PdfPageCrop(10, 10))
            captureWindow().useBitmap { assertTrue("Repeat apply must reset exact geometry", baseline.sameAs(it)) }
            assertEquals(PdfFitMode.PAGE, fixture.fit.value.mode)
            assertEquals(listOf(0 to 240), fixture.requests.toList())
            assertEquals(listOf(0), fixture.pages.toList()); assertEquals(1, fixture.completions.size)
        } finally { baseline.recycle() }
    }

    @Test fun `width fit pan clamps to the cropped bottom and repeat apply returns to its top`() {
        val fixture = show(panBitmap()); waitForPage(fixture, 0)
        fit(fixture, PdfFitMode.WIDTH); apply(fixture, PdfPageCrop(0, 10))
        assertEquals(Color.GREEN, sample(fixture, 120f, 10f))
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(Offset(center.x, 220f)); moveTo(Offset(center.x, 20f)); up()
        }
        assertEquals(Color.MAGENTA, sample(fixture, 120f, 230f))
        assertEquals(Color.WHITE, sample(fixture, 120f, 10f))
        apply(fixture, PdfPageCrop(0, 10))
        assertEquals(Color.GREEN, sample(fixture, 120f, 10f))
        assertEquals(Color.WHITE, sample(fixture, 120f, 230f))
        assertEquals(PdfFitMode.WIDTH, fixture.fit.value.mode)
        assertEquals(listOf(0 to 240), fixture.requests.toList())
        assertEquals(listOf(0), fixture.pages.toList())
    }

    @Test fun `height changes clamp cropped pan and width or page changes retain crop and fit without duplicate progress`() {
        val fixture = show(panBitmap()); waitForPage(fixture, 0)
        fit(fixture, PdfFitMode.WIDTH); apply(fixture, PdfPageCrop(0, 10))
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(Offset(center.x, 220f)); moveTo(Offset(center.x, 20f)); up()
        }
        compose.runOnIdle { fixture.height.value = 360 }; compose.waitForIdle()
        assertEquals(Color.MAGENTA, sample(fixture, 120f, 350f))
        assertEquals(listOf(0 to 240), fixture.requests.toList()); assertEquals(listOf(0), fixture.pages.toList())
        compose.runOnIdle { fixture.width.value = 300 }; waitForPage(fixture, 0)
        assertEquals(Color.GREEN, sample(fixture, 150f, 10f))
        assertEquals(Color.WHITE, sample(fixture, 150f, 350f))
        compose.runOnIdle { fixture.status.goTo(1) }; waitForPage(fixture, 1)
        assertEquals(Color.GREEN, sample(fixture, 150f, 10f))
        assertEquals(PdfPageCrop(0, 10), fixture.crop.value.crop)
        assertEquals(PdfFitMode.WIDTH, fixture.fit.value.mode)
        assertEquals(listOf(0 to 240, 0 to 300, 1 to 300), fixture.requests.toList())
        assertEquals(3, fixture.completions.size); assertEquals(listOf(0, 1), fixture.pages.toList())
    }

    @Test fun `swipe eligibility uses cropped width fit geometry and only actual navigation requests a new raster`() {
        val fixture = show(edgedBitmap()); waitForPage(fixture, 0)
        fit(fixture, PdfFitMode.WIDTH)
        swipeLeft()
        assertTrue(fixture.swipes.isEmpty()); assertEquals(0, fixture.status.requestedPage.value)
        apply(fixture, PdfPageCrop(0, 20))
        assertEquals(BACKGROUND, sample(fixture, 120f, 230f))
        assertEquals(listOf(0 to 240), fixture.requests.toList()); assertEquals(listOf(0), fixture.pages.toList())
        swipeLeft(); waitForPage(fixture, 1)
        assertEquals(listOf(1), fixture.swipes.toList())
        assertEquals(listOf(0 to 240, 1 to 240), fixture.requests.toList())
        assertEquals(listOf(0, 1), fixture.pages.toList()); assertEquals(PdfPageCrop(0, 20), fixture.crop.value.crop)
    }

    @Test fun `night changes preserve crop zoom and pan with source pixels unchanged`() {
        val source = edgedBitmap(); val original = pixels(source)
        val fixture = show(source); waitForPage(fixture, 0)
        apply(fixture, PdfPageCrop(10, 10))
        compose.onNodeWithTag(VIEWPORT).performTouchInput { doubleClick(center) }
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            val first = center + Offset(-24f, -12f); val second = center + Offset(24f, 12f)
            down(0, first); down(1, second)
            moveTo(0, first + Offset(30f, 15f)); moveTo(1, second + Offset(30f, 15f))
            up(0); up(1)
        }
        val manualView = captureWindow()
        try {
            compose.runOnIdle { fixture.night.value = true }
            captureWindow().useBitmap { assertFalse(manualView.sameAs(it)) }
            compose.runOnIdle { fixture.night.value = false }
            captureWindow().useBitmap { assertTrue("Night toggle must preserve crop/zoom/pan", manualView.sameAs(it)) }
            assertEquals(listOf(0 to 240), fixture.requests.toList()); assertEquals(listOf(0), fixture.pages.toList())
            assertArrayEquals(original, pixels(source))
        } finally { manualView.recycle() }
    }

    @Test fun `crop while viewport is collapsed waits for display geometry without restarting its raster`() {
        val fixture = show(edgedBitmap()); waitForPage(fixture, 0)
        compose.runOnIdle { fixture.height.value = 0 }; compose.waitForIdle()
        apply(fixture, PdfPageCrop(10, 10))
        compose.runOnIdle { fixture.height.value = 240 }; compose.waitForIdle()
        assertEquals(Color.WHITE, sample(fixture, 120f, 10f))
        assertEquals(listOf(0 to 240), fixture.requests.toList()); assertEquals(1, fixture.completions.size)
        assertEquals(listOf(0), fixture.pages.toList())
    }

    @Test fun `crop does not implicitly retry a failed page and explicit native retry keeps the chosen crop`() {
        val fixture = show(edgedBitmap(), failRender = true)
        compose.waitForIdle(); compose.waitUntil(5_000) { fixture.status.renderFailed.value }
        val failedRequests = fixture.requests.toList()
        apply(fixture, PdfPageCrop(10, 10))
        compose.onNodeWithText("Yeniden dene").assertIsDisplayed()
        assertEquals(failedRequests, fixture.requests.toList())
        assertTrue(fixture.status.renderFailed.value); assertTrue(fixture.pages.isEmpty())
        compose.runOnIdle { fixture.failRender = false }
        compose.onNodeWithText("Yeniden dene").performClick(); waitForPage(fixture, 0)
        assertEquals(Color.WHITE, sample(fixture, 120f, 10f))
        assertEquals(2, fixture.requests.size); assertEquals(1, fixture.completions.size)
        assertEquals(listOf(0), fixture.pages.toList()); assertFalse(fixture.status.renderFailed.value)
    }

    @Test fun `one pixel page stays drawable under maximum crop and night filtering`() {
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val fixture = show(source); waitForPage(fixture, 0)
        apply(fixture, PdfPageCrop(20, 20))
        assertEquals(Color.GREEN, sample(fixture, 120f, 120f))
        compose.runOnIdle { fixture.night.value = true }
        assertEquals(Color.MAGENTA, sample(fixture, 120f, 120f))
        assertEquals(Color.GREEN, source.getPixel(0, 0))
        assertEquals(listOf(0 to 240), fixture.requests.toList()); assertEquals(listOf(0), fixture.pages.toList())
    }

    private fun apply(fixture: Fixture, crop: PdfPageCrop) {
        compose.runOnIdle { fixture.crop.value = PdfCropRequest(crop, fixture.crop.value.revision + 1) }
        compose.waitForIdle()
    }

    private fun fit(fixture: Fixture, mode: PdfFitMode) {
        compose.runOnIdle { fixture.fit.value = PdfFitRequest(mode, fixture.fit.value.revision + 1) }
        compose.waitForIdle()
    }

    private fun swipeLeft() {
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(center + Offset(80f, 0f)); moveTo(center + Offset(-80f, 0f)); up()
        }
        compose.waitForIdle()
    }

    private fun show(source: Bitmap, failRender: Boolean = false): Fixture {
        val fixture = Fixture(source, failRender)
        compose.setContent { YaprakTheme {
            val page by fixture.status.requestedPage.collectAsState()
            LaunchedEffect(fixture) { fixture.status.renderedPage.filterNotNull().collect { fixture.pages += it } }
            Box(Modifier.size(fixture.width.value.dp, fixture.height.value.dp).testTag(VIEWPORT)
                .onGloballyPositioned { fixture.bounds = it.boundsInWindow() }) {
                PdfPage(fixture.renderer, page, {}, fixture.status::startRender, { rendered, ticket ->
                    fixture.completions += rendered to ticket
                    fixture.status.finishRender(rendered, ticket)
                }, nightMode = fixture.night.value, onSwipePage = { delta ->
                    fixture.swipes += delta; fixture.status.move(delta)
                }, onRenderFailed = fixture.status::failRender, fitRequest = fixture.fit.value,
                    cropRequest = fixture.crop.value)
            }
        } }
        return fixture
    }

    private fun waitForPage(fixture: Fixture, page: Int) {
        compose.waitForIdle()
        compose.waitUntil(5_000) { fixture.status.renderedPage.value == page && fixture.pages.lastOrNull() == page }
        compose.waitForIdle()
    }

    private fun edgedBitmap() = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val canvas = Canvas(this); val paint = Paint()
        paint.color = Color.RED; canvas.drawRect(0f, 0f, 24f, 320f, paint)
        paint.color = Color.GREEN; canvas.drawRect(216f, 0f, 240f, 320f, paint)
        paint.color = Color.BLUE; canvas.drawRect(0f, 0f, 240f, 32f, paint)
        paint.color = Color.YELLOW; canvas.drawRect(0f, 288f, 240f, 320f, paint)
        paint.color = Color.BLACK; canvas.drawRect(72f, 96f, 120f, 160f, paint)
        paint.color = Color.CYAN; canvas.drawRect(132f, 184f, 180f, 240f, paint)
    }

    private fun panBitmap() = Bitmap.createBitmap(240, 480, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val canvas = Canvas(this); val paint = Paint()
        paint.color = Color.BLUE; canvas.drawRect(0f, 0f, 240f, 48f, paint)
        paint.color = Color.YELLOW; canvas.drawRect(0f, 432f, 240f, 480f, paint)
        paint.color = Color.GREEN; canvas.drawRect(0f, 48f, 240f, 96f, paint)
        paint.color = Color.MAGENTA; canvas.drawRect(0f, 384f, 240f, 432f, paint)
    }

    private fun sample(fixture: Fixture, x: Float, y: Float): Int = captureWindow().let { image ->
        try { image.getPixel((fixture.bounds.left + x).toInt(), (fixture.bounds.top + y).toInt()) }
        finally { image.recycle() }
    }

    private fun captureWindow(): Bitmap = compose.runOnIdle {
        val view = compose.activity.window.decorView
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private fun pixels(image: Bitmap) = IntArray(image.width * image.height).also {
        image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
    }

    private inline fun Bitmap.useBitmap(action: (Bitmap) -> Unit) { try { action(this) } finally { recycle() } }

    private class Fixture(source: Bitmap, var failRender: Boolean) {
        val status = PdfRenderStatus(3, 0)
        val crop = mutableStateOf(PdfCropRequest())
        val fit = mutableStateOf(PdfFitRequest())
        val night = mutableStateOf(false)
        val width = mutableStateOf(240); val height = mutableStateOf(240)
        var bounds = Rect.Zero
        val requests = CopyOnWriteArrayList<Pair<Int, Int>>()
        val completions = CopyOnWriteArrayList<Pair<Int, Long>>()
        val pages = CopyOnWriteArrayList<Int>()
        val swipes = CopyOnWriteArrayList<Int>()
        val renderer: suspend (Int, Int) -> Bitmap = { page, width ->
            requests += page to width
            if (failRender) throw IOException("Controlled native render failure")
            source
        }
    }

    private companion object {
        const val VIEWPORT = "pdf-crop-viewport"
        val BACKGROUND = Color.rgb(35, 39, 37)
    }
}
