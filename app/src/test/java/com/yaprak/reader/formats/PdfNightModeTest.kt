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
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.formats.pdf.PdfPage
import com.yaprak.reader.formats.pdf.PdfPosition
import com.yaprak.reader.formats.pdf.PdfRenderStatus
import com.yaprak.reader.formats.pdf.pdfNightColorFilter
import com.yaprak.reader.ui.theme.YaprakTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class PdfNightModeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `night toggle changes displayed pixels without rerender progress or bookmark changes`() {
        val source = stripeBitmap()
        val originalPixels = pixels(source)
        val night = mutableStateOf(false)
        val status = PdfRenderStatus(4, 1)
        val renderRequests = CopyOnWriteArrayList<Pair<Int, Int>>()
        val completions = CopyOnWriteArrayList<Pair<Int, Long>>()
        val snapshots = CopyOnWriteArrayList<ReadingLocation>()
        val renderer: suspend (Int, Int) -> Bitmap = { page, width ->
            renderRequests += page to width
            source
        }
        var bounds = Rect.Zero
        compose.setContent { YaprakTheme {
            val page by status.requestedPage.collectAsState()
            LaunchedEffect(status) {
                status.renderedPage.filterNotNull().collect { snapshots += PdfPosition.encode(it) }
            }
            Box(Modifier.size(240.dp).onGloballyPositioned { bounds = it.boundsInWindow() }) {
                PdfPage(renderer, page, {}, status::startRender, { rendered, ticket ->
                    completions += rendered to ticket
                    status.finishRender(rendered, ticket)
                }, nightMode = night.value)
            }
        } }
        waitForPage { status.renderedPage.value == 1 && snapshots.isNotEmpty() }
        compose.onNodeWithContentDescription("PDF sayfası 2").assertIsDisplayed()
        val requestsBefore = renderRequests.toList()
        val completionsBefore = completions.toList()
        val snapshotsBefore = snapshots.toList()
        val bookmarkBefore = bookmark(status)
        assertTrue(requestsBefore.isNotEmpty())
        assertEquals(1, status.requestedPage.value)
        assertEquals(PdfPosition.encode(1), snapshotsBefore.last())
        captureWindow().useBitmap { assertStripeColors(it, bounds, listOf(Color.WHITE, Color.BLACK, Color.RED)) }

        compose.runOnIdle { night.value = true }
        captureWindow().useBitmap { assertStripeColors(it, bounds, listOf(Color.BLACK, Color.WHITE, Color.CYAN)) }
        assertEquals(requestsBefore, renderRequests.toList())
        assertEquals(completionsBefore, completions.toList())
        assertEquals(snapshotsBefore, snapshots.toList())
        assertEquals(1, status.requestedPage.value)
        assertEquals(1, status.renderedPage.value)
        assertEquals(bookmarkBefore, bookmark(status))

        compose.runOnIdle { night.value = false }
        captureWindow().useBitmap { assertStripeColors(it, bounds, listOf(Color.WHITE, Color.BLACK, Color.RED)) }
        assertEquals(requestsBefore, renderRequests.toList())
        assertEquals(snapshotsBefore, snapshots.toList())
        assertEquals(bookmarkBefore, bookmark(status))
        assertArrayEquals(originalPixels, pixels(source))
    }

    @Test fun `night filter preserves alpha and never mutates source bitmap`() {
        val source = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.WHITE)
            setPixel(1, 0, Color.BLACK)
            setPixel(2, 0, Color.argb(128, 40, 80, 120))
            setPixel(3, 0, Color.TRANSPARENT)
        }
        val originalPixels = pixels(source)
        val transformed = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint().apply { colorFilter = pdfNightColorFilter.asAndroidColorFilter() }
            Canvas(transformed).drawBitmap(source, 0f, 0f, paint)
            assertEquals(Color.BLACK, transformed.getPixel(0, 0))
            assertEquals(Color.WHITE, transformed.getPixel(1, 0))
            for (x in 0 until source.width) {
                val before = source.getPixel(x, 0)
                val after = transformed.getPixel(x, 0)
                assertEquals(Color.alpha(before), Color.alpha(after))
                if (Color.alpha(before) != 0) {
                    assertChannelClose(255 - Color.red(before), Color.red(after))
                    assertChannelClose(255 - Color.green(before), Color.green(after))
                    assertChannelClose(255 - Color.blue(before), Color.blue(after))
                }
            }
            assertArrayEquals(originalPixels, pixels(source))
        } finally {
            source.recycle()
            transformed.recycle()
        }
    }

    @Test fun `night toggle preserves zoom and pan after real touch gestures`() {
        val source = landmarkBitmap()
        val night = mutableStateOf(false)
        val status = PdfRenderStatus(2, 0)
        val requests = CopyOnWriteArrayList<Pair<Int, Int>>()
        val renderer: suspend (Int, Int) -> Bitmap = { page, width ->
            requests += page to width
            source
        }
        compose.setContent { YaprakTheme {
            val page by status.requestedPage.collectAsState()
            Box(Modifier.size(240.dp).testTag("pdfViewport")) {
                PdfPage(renderer, page, {}, status::startRender, status::finishRender,
                    nightMode = night.value)
            }
        } }
        waitForPage { status.renderedPage.value == 0 }
        val requestsBefore = requests.toList()
        val original = captureWindow()
        val zoomed: Bitmap
        val panned: Bitmap
        try {
            compose.onNodeWithTag("pdfViewport").performTouchInput { doubleClick(center) }
            zoomed = captureWindow()
            try {
                assertFalse("Double tap must change the visible page", original.sameAs(zoomed))
                compose.onNodeWithTag("pdfViewport").performTouchInput {
                    val first = center + Offset(-24f, -12f)
                    val second = center + Offset(24f, 12f)
                    down(0, first)
                    down(1, second)
                    moveTo(0, first + Offset(20f, 15f))
                    moveTo(1, second + Offset(20f, 15f))
                    moveTo(0, first + Offset(50f, 35f))
                    moveTo(1, second + Offset(50f, 35f))
                    up(0)
                    up(1)
                }
                panned = captureWindow()
                try {
                    assertFalse("Transform gesture must pan the zoomed page", zoomed.sameAs(panned))
                    compose.runOnIdle { night.value = true }
                    captureWindow().useBitmap {
                        assertFalse("The night filter must still change the zoomed page", panned.sameAs(it))
                    }
                    compose.runOnIdle { night.value = false }
                    captureWindow().useBitmap {
                        assertTrue("Restoring colors must retain the exact zoom and pan", panned.sameAs(it))
                    }
                    assertEquals(requestsBefore, requests.toList())
                    assertEquals(0, status.requestedPage.value)
                    assertEquals(0, status.renderedPage.value)
                } finally {
                    panned.recycle()
                }
            } finally {
                zoomed.recycle()
            }
        } finally {
            original.recycle()
        }
    }

    private fun waitForPage(condition: () -> Boolean) {
        // Pump initial Compose work before waiting on the immediate fake renderer.
        compose.waitForIdle()
        compose.waitUntil(5_000, condition)
        compose.waitForIdle()
    }

    private fun bookmark(status: PdfRenderStatus): BookmarkTarget? = status.renderedPage.value?.let {
        BookmarkTarget("Sayfa ${it + 1}", PdfPosition.encode(it))
    }

    private fun stripeBitmap() = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        val paint = Paint()
        listOf(Color.WHITE, Color.BLACK, Color.RED).forEachIndexed { index, color ->
            paint.color = color
            canvas.drawRect(index * 80f, 0f, (index + 1) * 80f, 120f, paint)
        }
    }

    private fun landmarkBitmap() = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val canvas = Canvas(this)
        val paint = Paint().apply { color = Color.BLACK }
        canvas.drawRect(24f, 36f, 74f, 126f, paint)
        paint.color = Color.RED
        canvas.drawRect(118f, 44f, 172f, 100f, paint)
        paint.color = Color.BLUE
        canvas.drawRect(80f, 154f, 194f, 206f, paint)
        paint.color = Color.GREEN
        canvas.drawRect(202f, 114f, 232f, 178f, paint)
    }

    private fun assertStripeColors(bitmap: Bitmap, bounds: Rect, expected: List<Int>) {
        listOf(0.16f, 0.5f, 0.84f).forEachIndexed { index, fraction ->
            val x = (bounds.left + bounds.width * fraction).toInt()
            assertEquals(expected[index], bitmap.getPixel(x, bounds.center.y.toInt()))
        }
    }

    private fun assertChannelClose(expected: Int, actual: Int) {
        assertTrue("Expected channel $expected, got $actual", kotlin.math.abs(expected - actual) <= 2)
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun captureWindow(): Bitmap = compose.runOnIdle {
        val view = compose.activity.window.decorView
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
    }

    private inline fun Bitmap.useBitmap(action: (Bitmap) -> Unit) {
        try { action(this) } finally { recycle() }
    }
}
