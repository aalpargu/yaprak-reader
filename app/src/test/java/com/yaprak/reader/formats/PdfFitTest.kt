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
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.yaprak.reader.formats.pdf.PdfFitMode
import com.yaprak.reader.formats.pdf.PdfFitRequest
import com.yaprak.reader.formats.pdf.PdfPage
import com.yaprak.reader.formats.pdf.PdfRenderStatus
import com.yaprak.reader.ui.theme.YaprakTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `fit width fills the viewport from the top while page fit and night colors preserve the bitmap`() {
        val source = markedBitmap(480)
        val original = pixels(source)
        val fixture = show(source)
        waitForPage(fixture, 0)
        assertNotEquals(Color.WHITE, sample(fixture, 10f, 120f))
        assertEquals(Color.RED, sample(fixture, 120f, 230f))
        val requests = fixture.renderRequests.toList()
        val completions = fixture.completions.toList()
        val progress = fixture.visiblePages.toList()

        fit(fixture, PdfFitMode.WIDTH)
        assertEquals(Color.GREEN, sample(fixture, 10f, 10f))
        assertEquals(Color.WHITE, sample(fixture, 10f, 120f))
        assertEquals(Color.WHITE, sample(fixture, 120f, 230f))
        compose.runOnIdle { fixture.night.value = true }
        assertEquals(Color.MAGENTA, sample(fixture, 10f, 10f))
        fit(fixture, PdfFitMode.PAGE)
        assertEquals(Color.CYAN, sample(fixture, 120f, 230f))
        compose.runOnIdle { fixture.night.value = false }
        assertEquals(Color.RED, sample(fixture, 120f, 230f))
        assertEquals(requests, fixture.renderRequests.toList())
        assertEquals(completions, fixture.completions.toList())
        assertEquals(progress, fixture.visiblePages.toList())
        assertArrayEquals(original, pixels(source))
    }

    @Test fun `clipped width fit permits vertical pan but never page swipes and repeating it returns to the top`() {
        val fixture = show(markedBitmap(480))
        waitForPage(fixture, 0)
        fit(fixture, PdfFitMode.WIDTH)
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(Offset(center.x, 220f))
            moveTo(Offset(center.x, 20f))
            up()
        }
        assertEquals(Color.WHITE, sample(fixture, 120f, 10f))
        assertEquals(Color.RED, sample(fixture, 120f, 235f))
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(center + Offset(80f, 0f))
            moveTo(center + Offset(-80f, 0f))
            up()
        }
        assertEquals(emptyList<Int>(), fixture.swipes.toList())
        assertEquals(0, fixture.status.requestedPage.value)
        fit(fixture, PdfFitMode.WIDTH)
        assertEquals(Color.GREEN, sample(fixture, 120f, 10f))
        assertEquals(Color.WHITE, sample(fixture, 120f, 230f))
        assertEquals(listOf(0 to 240), fixture.renderRequests.toList())
        assertEquals(1, fixture.completions.size)
        assertEquals(listOf(0), fixture.visiblePages.toList())
    }

    @Test fun `repeating either fit command resets manual zoom even when its mode is already selected`() {
        val fixture = show(markedBitmap(480))
        waitForPage(fixture, 0)
        for (mode in listOf(PdfFitMode.PAGE, PdfFitMode.WIDTH)) {
            fit(fixture, mode)
            assertEquals(Color.GREEN, sample(fixture, 120f, 10f))
            compose.onNodeWithTag(VIEWPORT).performTouchInput { doubleClick(center) }
            assertEquals(Color.WHITE, sample(fixture, 120f, 10f))
            fit(fixture, mode)
            assertEquals(Color.GREEN, sample(fixture, 120f, 10f))
        }
        assertEquals(listOf(0 to 240), fixture.renderRequests.toList())
        assertEquals(1, fixture.completions.size)
        assertEquals(listOf(0), fixture.visiblePages.toList())
    }

    @Test fun `height resize clamps a scrolled page while width and page changes retain width fit and reset its view`() {
        val fixture = show(markedBitmap(480))
        waitForPage(fixture, 0)
        fit(fixture, PdfFitMode.WIDTH)
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(Offset(center.x, 220f))
            moveTo(Offset(center.x, 20f))
            up()
        }
        compose.runOnIdle { fixture.height.value = 360 }
        compose.waitForIdle()
        assertEquals(Color.RED, sample(fixture, 120f, 350f))
        assertEquals(listOf(0 to 240), fixture.renderRequests.toList())
        assertEquals(1, fixture.completions.size)
        compose.onNodeWithTag(VIEWPORT).performTouchInput { doubleClick(center) }
        compose.runOnIdle { fixture.width.value = 300 }
        compose.waitForIdle()
        compose.waitUntil(5_000) { fixture.renderRequests.size == 2 && fixture.status.renderedPage.value == 0 }
        assertEquals(Color.GREEN, sample(fixture, 10f, 10f))
        assertEquals(Color.WHITE, sample(fixture, 150f, 350f))
        compose.onNodeWithTag(VIEWPORT).performTouchInput { doubleClick(center) }
        compose.runOnIdle { fixture.status.goTo(1) }
        waitForPage(fixture, 1)
        assertEquals(Color.GREEN, sample(fixture, 10f, 10f))
        assertEquals(listOf(0 to 240, 0 to 300, 1 to 300), fixture.renderRequests.toList())
        assertEquals(3, fixture.completions.size)
    }

    @Test fun `short pages also start at the top in width fit and zoom is bounded without an empty gap`() {
        val fixture = show(markedBitmap(120))
        waitForPage(fixture, 0)
        assertNotEquals(Color.GREEN, sample(fixture, 120f, 10f))
        fit(fixture, PdfFitMode.WIDTH)
        assertEquals(Color.GREEN, sample(fixture, 120f, 10f))
        assertNotEquals(Color.RED, sample(fixture, 120f, 230f))
        compose.onNodeWithTag(VIEWPORT).performTouchInput { doubleClick(center) }
        assertEquals(Color.GREEN, sample(fixture, 120f, 10f))
        assertEquals(Color.RED, sample(fixture, 120f, 230f))
        fit(fixture, PdfFitMode.WIDTH)
        assertEquals(Color.GREEN, sample(fixture, 120f, 10f))
        assertNotEquals(Color.RED, sample(fixture, 120f, 230f))
        assertEquals(listOf(0 to 240), fixture.renderRequests.toList())
    }

    private fun fit(fixture: Fixture, mode: PdfFitMode) {
        compose.runOnIdle {
            fixture.fit.value = PdfFitRequest(mode, fixture.fit.value.revision + 1)
        }
        compose.waitForIdle()
    }

    private fun show(source: Bitmap): Fixture {
        val fixture = Fixture(source)
        compose.setContent { YaprakTheme {
            val page by fixture.status.requestedPage.collectAsState()
            LaunchedEffect(fixture) {
                fixture.status.renderedPage.filterNotNull().collect { fixture.visiblePages += it }
            }
            Box(Modifier.size(fixture.width.value.dp, fixture.height.value.dp).testTag(VIEWPORT)
                .onGloballyPositioned { fixture.bounds = it.boundsInWindow() }) {
                PdfPage(fixture.renderer, page, {}, fixture.status::startRender, { rendered, ticket ->
                    fixture.completions += rendered to ticket
                    fixture.status.finishRender(rendered, ticket)
                }, nightMode = fixture.night.value, onSwipePage = { delta ->
                    fixture.swipes += delta
                    fixture.status.move(delta)
                }, onRenderFailed = fixture.status::failRender, fitRequest = fixture.fit.value)
            }
        } }
        return fixture
    }

    private fun waitForPage(fixture: Fixture, page: Int) {
        compose.waitForIdle()
        compose.waitUntil(5_000) { fixture.status.renderedPage.value == page && fixture.visiblePages.lastOrNull() == page }
        compose.waitForIdle()
    }

    private fun markedBitmap(height: Int) = Bitmap.createBitmap(240, height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val canvas = Canvas(this)
        val paint = Paint().apply { color = Color.GREEN }
        canvas.drawRect(0f, 0f, 240f, 48f, paint)
        paint.color = Color.RED
        canvas.drawRect(0f, height - 48f, 240f, height.toFloat(), paint)
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun sample(fixture: Fixture, x: Float, y: Float): Int = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            bitmap.getPixel((fixture.bounds.left + x).toInt(), (fixture.bounds.top + y).toInt())
        } finally { bitmap.recycle() }
    }

    private class Fixture(source: Bitmap) {
        val status = PdfRenderStatus(3, 0)
        val fit = mutableStateOf(PdfFitRequest())
        val night = mutableStateOf(false)
        val width = mutableStateOf(240)
        val height = mutableStateOf(240)
        var bounds = Rect.Zero
        val renderRequests = CopyOnWriteArrayList<Pair<Int, Int>>()
        val completions = CopyOnWriteArrayList<Pair<Int, Long>>()
        val visiblePages = CopyOnWriteArrayList<Int>()
        val swipes = CopyOnWriteArrayList<Int>()
        val renderer: suspend (Int, Int) -> Bitmap = { page, width -> renderRequests += page to width; source }
    }

    private companion object { const val VIEWPORT = "pdf-fit-viewport" }
}
