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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.yaprak.reader.formats.pdf.PdfPage
import com.yaprak.reader.formats.pdf.PdfRenderStatus
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfViewportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `height changes retain displayed zoom without rendering or progress callbacks`() {
        val fixture = show(stripeBitmap())
        waitForPage(fixture, 0)
        assertWideColors(fixture, Color.WHITE, Color.RED)
        compose.onNodeWithTag(VIEWPORT).performTouchInput { doubleClick(center) }
        assertWideColors(fixture, Color.BLACK, Color.BLACK)
        val requests = fixture.renderRequests.toList()
        val completions = fixture.completions.toList()
        val progress = fixture.visiblePages.toList()

        for (height in listOf(360, 200, 240)) {
            compose.runOnIdle { fixture.height.value = height }
            compose.waitForIdle()
            assertWideColors(fixture, Color.BLACK, Color.BLACK)
            assertEquals(requests, fixture.renderRequests.toList())
            assertEquals(completions, fixture.completions.toList())
            assertEquals(progress, fixture.visiblePages.toList())
            assertEquals(0, fixture.status.requestedPage.value)
            assertEquals(0, fixture.status.renderedPage.value)
        }
    }

    @Test fun `shrinking the height bounds an existing pan while retaining enlarged page pixels`() {
        val fixture = show(tallBitmap(), height = 360)
        waitForPage(fixture, 0)
        compose.onNodeWithTag(VIEWPORT).performTouchInput { doubleClick(center) }
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(Offset(center.x, 40f))
            moveTo(Offset(center.x, 320f))
            up()
        }
        val requests = fixture.renderRequests.toList()
        compose.runOnIdle { fixture.height.value = 240 }
        compose.waitForIdle()
        captureWindow().useBitmap { bitmap ->
            val bounds = fixture.bounds
            // A stale pan leaves empty background above the page. Resetting zoom leaves
            // background at the left. Retained zoom plus bounded pan shows this corner.
            assertEquals(Color.GREEN, bitmap.getPixel((bounds.left + 10).toInt(), (bounds.top + 10).toInt()))
            assertEquals(Color.GREEN, bitmap.getPixel(bounds.center.x.toInt(), (bounds.top + 10).toInt()))
        }
        assertEquals(requests, fixture.renderRequests.toList())
        assertEquals(listOf(0), fixture.visiblePages.toList())
        assertEquals(0, fixture.status.renderedPage.value)
    }

    @Test fun `new width renders again and width or page changes reset displayed zoom`() {
        val fixture = show(stripeBitmap())
        waitForPage(fixture, 0)
        compose.onNodeWithTag(VIEWPORT).performTouchInput { doubleClick(center) }
        assertWideColors(fixture, Color.BLACK, Color.BLACK)
        compose.runOnIdle { fixture.width.value = 300 }
        compose.waitForIdle()
        compose.waitUntil(5_000) { fixture.renderRequests.size == 2 && fixture.status.renderedPage.value == 0 }
        assertWideColors(fixture, Color.WHITE, Color.RED)
        assertEquals(listOf(0 to 240, 0 to 300), fixture.renderRequests.toList())

        compose.onNodeWithTag(VIEWPORT).performTouchInput { doubleClick(center) }
        assertWideColors(fixture, Color.BLACK, Color.BLACK)
        compose.runOnIdle { fixture.status.goTo(1) }
        waitForPage(fixture, 1)
        assertWideColors(fixture, Color.WHITE, Color.RED)
        assertEquals(listOf(0 to 240, 0 to 300, 1 to 300), fixture.renderRequests.toList())
        assertEquals(3, fixture.completions.size)
    }

    @Test fun `a zero height waits for a real viewport and reopening it retains the completed bitmap`() {
        val fixture = show(stripeBitmap(), height = 0)
        compose.waitForIdle()
        assertEquals(emptyList<Pair<Int, Int>>(), fixture.renderRequests.toList())
        assertNull(fixture.status.renderedPage.value)
        compose.runOnIdle { fixture.height.value = 240 }
        waitForPage(fixture, 0)
        assertEquals(listOf(0 to 240), fixture.renderRequests.toList())
        compose.runOnIdle { fixture.height.value = 0 }
        compose.waitForIdle()
        compose.runOnIdle { fixture.height.value = 240 }
        compose.waitForIdle()
        assertWideColors(fixture, Color.WHITE, Color.RED)
        assertEquals(listOf(0 to 240), fixture.renderRequests.toList())
        assertEquals(1, fixture.completions.size)
        assertEquals(listOf(0), fixture.visiblePages.toList())
    }

    @Test fun `a failed render stays failed across height and width changes until explicit retry`() {
        val fixture = show(stripeBitmap(), failRender = true)
        compose.waitForIdle()
        compose.onNodeWithText("Bu PDF sayfası görüntülenemedi.").assertExists()
        compose.runOnIdle { fixture.height.value = 360 }
        compose.waitForIdle()
        compose.onNodeWithText("Bu PDF sayfası görüntülenemedi.").assertExists()
        compose.runOnIdle { fixture.width.value = 280 }
        compose.waitForIdle()
        compose.onNodeWithText("Bu PDF sayfası görüntülenemedi.").assertExists()
        assertEquals(listOf(0 to 240), fixture.renderRequests.toList())
        assertNull(fixture.status.renderedPage.value)
        assertTrue(fixture.status.renderFailed.value)
        assertEquals(1, fixture.failures.size)
        compose.runOnIdle { fixture.failRender = false }
        compose.onNodeWithText("Yeniden dene").performClick()
        waitForPage(fixture, 0)
        assertEquals(listOf(0 to 240, 0 to 280), fixture.renderRequests.toList())
        assertEquals(1, fixture.completions.size)
        assertFalse(fixture.status.renderFailed.value)
        assertEquals(1, fixture.failures.size)
    }

    private fun show(source: Bitmap, height: Int = 240, failRender: Boolean = false): Fixture {
        val fixture = Fixture(source, height, failRender)
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
                }, onRenderFailed = { failed, ticket ->
                    fixture.failures += failed to ticket
                    fixture.status.failRender(failed, ticket)
                })
            }
        } }
        return fixture
    }

    private fun waitForPage(fixture: Fixture, page: Int) {
        compose.waitForIdle()
        compose.waitUntil(5_000) { fixture.status.renderedPage.value == page && fixture.visiblePages.lastOrNull() == page }
        compose.waitForIdle()
    }

    private fun assertWideColors(fixture: Fixture, left: Int, right: Int) {
        captureWindow().useBitmap { bitmap ->
            val bounds = fixture.bounds
            assertEquals(left, bitmap.getPixel((bounds.left + bounds.width * 0.25f).toInt(), bounds.center.y.toInt()))
            assertEquals(right, bitmap.getPixel((bounds.left + bounds.width * 0.75f).toInt(), bounds.center.y.toInt()))
        }
    }

    private fun stripeBitmap() = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        val paint = Paint()
        listOf(Color.WHITE, Color.BLACK, Color.RED).forEachIndexed { index, color ->
            paint.color = color
            canvas.drawRect(index * 80f, 0f, (index + 1) * 80f, 120f, paint)
        }
    }

    private fun tallBitmap() = Bitmap.createBitmap(240, 480, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        Canvas(this).drawRect(0f, 0f, 240f, 48f, Paint().apply { color = Color.GREEN })
    }

    private fun captureWindow(): Bitmap = compose.runOnIdle {
        val view = compose.activity.window.decorView
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private inline fun Bitmap.useBitmap(action: (Bitmap) -> Unit) {
        try { action(this) } finally { recycle() }
    }

    private class Fixture(source: Bitmap, height: Int, var failRender: Boolean) {
        val width = mutableStateOf(240)
        val height = mutableStateOf(height)
        var bounds = Rect.Zero
        val status = PdfRenderStatus(3, 0)
        val renderRequests = CopyOnWriteArrayList<Pair<Int, Int>>()
        val completions = CopyOnWriteArrayList<Pair<Int, Long>>()
        val failures = CopyOnWriteArrayList<Pair<Int, Long>>()
        val visiblePages = CopyOnWriteArrayList<Int>()
        val renderer: suspend (Int, Int) -> Bitmap = { page, width ->
            renderRequests += page to width
            if (failRender) throw IOException("Render failed")
            source
        }
    }

    private companion object { const val VIEWPORT = "pdf-resizable-viewport" }
}
