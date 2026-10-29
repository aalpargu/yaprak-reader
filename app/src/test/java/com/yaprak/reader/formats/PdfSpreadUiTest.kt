package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Two actual Compose page painters; colored fixtures are controlled raster evidence, not native PDF acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1000dp-h700dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfSpreadUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `wide spread displays distinct real page rasters and narrow fallback keeps the exact anchor`() {
        val f = show()
        waitReady(f)
        compose.onNodeWithContentDescription("PDF sayfası 2").assertExists()
        compose.onNodeWithContentDescription("PDF sayfası 3").assertExists()
        assertEquals(setOf(1, 2), f.requests.map { it.first }.toSet())
        assertEquals(1, f.status.requestedPage.value)
        assertEquals(Color.RED, sample(f, .25f)); assertEquals(Color.BLUE, sample(f, .75f))
        saveImage("spread-wide.png")
        // A selected right-hand page becomes the next exact anchor; its retained old painter
        // cannot leave the new render plan waiting forever for a completion it never emits.
        compose.runOnIdle { f.status.goTo(2) }
        waitReady(f, 2)
        assertEquals(listOf(2, 3), f.requests.takeLast(2).map { it.first }.sorted())
        compose.runOnIdle { f.status.goTo(1) }
        waitReady(f)
        compose.runOnIdle { f.width.value = 599 }
        waitReady(f)
        assertFalse(f.status.doublePage.value)
        assertEquals(1, f.status.renderedPage.value)
        compose.onNodeWithContentDescription("PDF sayfası 3").assertDoesNotExist()
        assertEquals(Color.RED, sample(f, .5f))
        compose.runOnIdle { f.width.value = 600 }
        waitReady(f)
        assertTrue(f.status.doublePage.value)
        assertEquals(1, f.status.renderedPage.value)
        compose.onNodeWithContentDescription("PDF sayfası 3").assertExists()
        saveImage("spread-threshold.png")
    }

    @Test fun `crop night and fit apply independently to both rasters without mutating source or issuing new render requests`() {
        val f = show()
        waitReady(f)
        val requests = f.requests.toList()
        compose.runOnIdle {
            f.crop.value = PdfCropRequest(PdfPageCrop(20, 20), 1)
            f.night.value = true
        }
        compose.waitForIdle()
        assertEquals(Color.CYAN, sample(f, .25f)); assertEquals(Color.YELLOW, sample(f, .75f))
        assertEquals(Color.WHITE, f.left.getPixel(0, 0)); assertEquals(Color.RED, f.left.getPixel(100, 200))
        assertEquals(Color.WHITE, f.right.getPixel(0, 0)); assertEquals(Color.BLUE, f.right.getPixel(100, 200))
        compose.runOnIdle { f.fit.value = PdfFitRequest(PdfFitMode.WIDTH, 1) }
        compose.waitForIdle()
        assertEquals(Color.CYAN, sample(f, .25f)); assertEquals(Color.YELLOW, sample(f, .75f))
        assertEquals(requests, f.requests.toList()); assertEquals(1, f.status.renderedPage.value)
        saveImage("spread-crop-night.png")
    }

    @Test fun `a pending second page cannot publish resume visibility and explicit retry recovers a failed pane`() {
        val pending = CompletableDeferred<Unit>()
        var failRight = true
        val f = show { page -> if (page == 2) { pending.await(); if (failRight) throw IOException("Controlled failure") } }
        compose.waitForIdle()
        compose.waitUntil(5_000) { f.requests.size == 2 }
        assertNull(f.status.renderedPage.value)
        compose.runOnIdle { pending.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithText("Bu PDF sayfası görüntülenemedi.").assertExists()
        assertTrue(f.status.renderFailed.value); assertNull(f.status.renderedPage.value)
        val attempts = f.requests.size
        compose.runOnIdle { f.width.value = 850 }
        compose.waitForIdle()
        assertTrue(f.status.renderFailed.value)
        assertEquals(1, f.requests.drop(attempts).size) // Only the valid neighbor is resized.
        compose.runOnIdle { failRight = false }
        compose.onNodeWithText("Yeniden dene").performClick()
        waitReady(f)
        assertFalse(f.status.renderFailed.value)
        assertEquals(1, f.status.renderedPage.value)
    }

    @Test fun `disposing the painter clears readiness and an odd last page fills one pane without fictitious page`() {
        val f = show()
        waitReady(f)
        compose.runOnIdle { f.status.goTo(4) }
        waitReady(f, 4)
        compose.onNodeWithContentDescription("PDF sayfası 5").assertExists()
        compose.onNodeWithContentDescription("PDF sayfası 6").assertDoesNotExist()
        assertEquals(4, f.requests.last().first)
        assertFalse(f.status.turn(1))
        compose.runOnIdle { f.present.value = false }
        compose.waitForIdle()
        assertNull(f.status.renderedPage.value)
    }

    private fun show(before: suspend (Int) -> Unit = {}): Fixture {
        val f = Fixture()
        compose.setContent { YaprakTheme {
            val page by f.status.requestedPage.collectAsState()
            if (f.present.value) Box(Modifier.size(f.width.value.dp, 500.dp)
                .onGloballyPositioned { f.bounds = it.boundsInWindow() }) {
                PdfPages({ index, width ->
                    f.requests += index to width
                    before(index)
                    if (index == 1) f.left else f.right
                }, page, 5, true, f.status, {}, f.status::turn, f.night.value, f.fit.value, f.crop.value)
            }
        } }
        return f
    }

    private fun waitReady(f: Fixture, page: Int = 1) {
        compose.waitForIdle()
        compose.waitUntil(5_000) { f.status.renderedPage.value == page }
        compose.waitForIdle()
    }
    private fun sample(f: Fixture, x: Float): Int = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(image))
            image.getPixel((f.bounds.left + f.bounds.width * x).toInt(), f.bounds.center.y.toInt())
        } finally { image.recycle() }
    }
    private fun saveImage(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(image))
            val path = File(System.getProperty("yaprak.screenshotDir"), "pdf-spread-0.90.0/$name")
            path.parentFile!!.mkdirs()
            path.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
    private class Fixture {
        val status = PdfRenderStatus(5, 1)
        val width = mutableStateOf(800)
        val night = mutableStateOf(false)
        val crop = mutableStateOf(PdfCropRequest())
        val fit = mutableStateOf(PdfFitRequest())
        val present = mutableStateOf(true)
        val requests = CopyOnWriteArrayList<Pair<Int, Int>>()
        var bounds = Rect.Zero
        val left = colored(Color.RED)
        val right = colored(Color.BLUE)
        companion object {
            fun colored(color: Int) = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                Canvas(this).drawRect(20f, 40f, 180f, 360f, Paint().apply { this.color = color })
            }
        }
    }
}
