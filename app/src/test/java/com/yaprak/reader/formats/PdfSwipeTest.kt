package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.yaprak.reader.formats.pdf.PdfPage
import com.yaprak.reader.formats.pdf.PdfRenderStatus
import com.yaprak.reader.ui.theme.YaprakTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfSwipeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `single finger horizontal swipes navigate once on release and progress waits for completed render`() {
        val fixture = show()
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { fixture.renderGate = gate }
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(center + Offset(80f, 0f))
            moveTo(center + Offset(-80f, 0f))
        }
        compose.runOnIdle {
            assertEquals(emptyList<Int>(), fixture.requests.toList())
            assertEquals(1, fixture.status.requestedPage.value)
            assertEquals(listOf(1), fixture.visiblePages.toList())
        }
        compose.onNodeWithTag(VIEWPORT).performTouchInput { up() }
        compose.waitUntil(5_000) { fixture.status.requestedPage.value == 2 }
        compose.runOnIdle {
            assertEquals(listOf(1), fixture.requests.toList())
            assertNull(fixture.status.renderedPage.value)
            assertEquals(listOf(1), fixture.visiblePages.toList())
            fixture.renderGate = null
            gate.complete(Unit)
        }
        waitForPage(fixture, 2)
        assertEquals(listOf(1, 2), fixture.visiblePages.toList())
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(center + Offset(-80f, 0f))
            moveTo(center + Offset(80f, 0f))
            up()
        }
        waitForPage(fixture, 1)
        assertEquals(listOf(1, -1), fixture.requests.toList())
        assertEquals(listOf(1, 2, 1), fixture.visiblePages.toList())
        assertEquals("Page swipes must not open the toolbar", 0, fixture.taps)
    }

    @Test fun `small vertical tap double tap and zoomed pan gestures do not request another page`() {
        val fixture = show()
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(center)
            moveTo(center + Offset(-20f, 0f))
            up()
        }
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(center + Offset(0f, -50f))
            moveTo(center + Offset(-40f, 50f))
            up()
        }
        compose.onNodeWithTag(VIEWPORT).performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals(1, fixture.taps)
        compose.onNodeWithTag(VIEWPORT).performTouchInput { doubleClick(center) }
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(center + Offset(80f, 0f))
            moveTo(center + Offset(-80f, 0f))
            up()
        }
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            val first = center + Offset(-24f, -12f)
            val second = center + Offset(24f, 12f)
            down(0, first)
            down(1, second)
            moveTo(0, first + Offset(30f, 10f))
            moveTo(1, second + Offset(30f, 10f))
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), fixture.requests.toList())
        assertEquals(1, fixture.status.requestedPage.value)
        assertEquals(listOf(1), fixture.visiblePages.toList())
    }

    @Test fun `a second pointer rejects the whole gesture after only one pointer remains`() {
        val fixture = show()
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(0, center + Offset(70f, -12f))
            down(1, center + Offset(20f, 12f))
            moveTo(0, center + Offset(60f, -12f))
            moveTo(1, center + Offset(10f, 12f))
            up(1)
            moveTo(0, center + Offset(-80f, -12f))
            up(0)
        }
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), fixture.requests.toList())
        assertEquals(1, fixture.status.requestedPage.value)
        assertEquals(listOf(1), fixture.visiblePages.toList())
    }

    @Test fun `Android cancellation rejects a swipe but real up with final movement accepts it`() {
        val fixture = show()
        val start = fixture.bounds.center + Offset(80f, 0f)
        val end = fixture.bounds.center + Offset(-80f, 0f)
        val cancelledAt = SystemClock.uptimeMillis()
        dispatch(cancelledAt, cancelledAt, MotionEvent.ACTION_DOWN, start)
        dispatch(cancelledAt, cancelledAt + 32, MotionEvent.ACTION_MOVE, end)
        dispatch(cancelledAt, cancelledAt + 64, MotionEvent.ACTION_CANCEL, end)
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), fixture.requests.toList())
        assertEquals(listOf(1), fixture.visiblePages.toList())
        assertEquals("Cancelled swipes must not open the toolbar", 0, fixture.taps)

        val releasedAt = SystemClock.uptimeMillis() + 400
        dispatch(releasedAt, releasedAt, MotionEvent.ACTION_DOWN, start)
        // Android may report the final displacement only in the UP event. This is
        // different from ACTION_CANCEL even when another recognizer consumes UP.
        dispatch(releasedAt, releasedAt + 64, MotionEvent.ACTION_UP, end)
        waitForPage(fixture, 2)
        assertEquals(listOf(1), fixture.requests.toList())
        assertEquals(listOf(1, 2), fixture.visiblePages.toList())
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals("Final UP movement must turn the page without a delayed toolbar tap", 0, fixture.taps)
    }

    @Test fun `rejected first and last page boundaries preserve the completed page`() {
        val fixture = show(initialPage = 0, pageCount = 2)
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(center + Offset(-80f, 0f))
            moveTo(center + Offset(80f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(-1), fixture.requests.toList())
        assertEquals(0, fixture.status.requestedPage.value)
        assertEquals(0, fixture.status.renderedPage.value)
        assertEquals(listOf(0), fixture.visiblePages.toList())
        compose.runOnIdle { fixture.status.goTo(1) }
        waitForPage(fixture, 1)
        compose.onNodeWithTag(VIEWPORT).performTouchInput {
            down(center + Offset(80f, 0f))
            moveTo(center + Offset(-80f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(-1, 1), fixture.requests.toList())
        assertEquals(1, fixture.status.requestedPage.value)
        assertEquals(1, fixture.status.renderedPage.value)
        assertEquals(listOf(0, 1), fixture.visiblePages.toList())
    }

    private fun show(initialPage: Int = 1, pageCount: Int = 3): Fixture {
        val fixture = Fixture(pageCount, initialPage)
        compose.setContent { YaprakTheme {
            val page by fixture.status.requestedPage.collectAsState()
            LaunchedEffect(fixture) {
                fixture.status.renderedPage.filterNotNull().collect { fixture.visiblePages += it }
            }
            Box(Modifier.size(240.dp).testTag(VIEWPORT)
                .onGloballyPositioned { fixture.bounds = it.boundsInWindow() }) {
                PdfPage(fixture.renderer, page, { fixture.taps++ }, fixture.status::startRender,
                    fixture.status::finishRender, onSwipePage = { delta ->
                        fixture.requests += delta
                        fixture.status.move(delta)
                    })
            }
        } }
        waitForPage(fixture, initialPage)
        return fixture
    }

    private fun waitForPage(fixture: Fixture, page: Int) {
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            fixture.status.renderedPage.value == page && fixture.visiblePages.lastOrNull() == page
        }
        compose.waitForIdle()
    }

    private fun dispatch(downTime: Long, eventTime: Long, action: Int, position: Offset) {
        compose.runOnIdle {
            val event = MotionEvent.obtain(downTime, eventTime, action, position.x, position.y, 0)
            try { compose.activity.window.decorView.dispatchTouchEvent(event) }
            finally { event.recycle() }
        }
        compose.waitForIdle()
    }

    private class Fixture(pageCount: Int, initialPage: Int) {
        val status = PdfRenderStatus(pageCount, initialPage)
        val requests = CopyOnWriteArrayList<Int>()
        val visiblePages = CopyOnWriteArrayList<Int>()
        var taps = 0
        var bounds = Rect.Zero
        @Volatile var renderGate: CompletableDeferred<Unit>? = null
        private val source = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val renderer: suspend (Int, Int) -> Bitmap = { _, _ -> renderGate?.await(); source }
    }

    private companion object { const val VIEWPORT = "pdf-swipe-viewport" }
}
