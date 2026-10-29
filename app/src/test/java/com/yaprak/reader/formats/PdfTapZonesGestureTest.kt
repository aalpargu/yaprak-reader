package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.yaprak.reader.domain.settings.TapZone
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.ReaderTap
import com.yaprak.reader.ui.theme.YaprakTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w891dp-h411dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfTapZonesGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Fixture {
        val taps = mutableListOf<ReaderTap>()
        var handled = true
        var fallback = 0
        var selections = 0
        val swipes = mutableListOf<Int>()
        val status = PdfRenderStatus(4, 0)
    }
    private fun show(spread: Boolean): Fixture {
        val f = Fixture()
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        compose.setContent { YaprakTheme {
            Box(Modifier.size(if (spread) 720.dp else 300.dp, 240.dp)) {
                PdfPages({ _, _ -> bitmap }, 0, 4, spread, f.status, { f.fallback++ },
                    { f.swipes += it; true },
                    onTextSelection = { _, _, _ -> f.selections++ },
                    onReaderTap = { f.taps += it; f.handled })
            }
        } }
        compose.waitUntil(5_000) { f.status.renderedPage.value == 0 }; compose.waitForIdle()
        return f
    }
    private fun tap(page: Int, fraction: Float) {
        compose.onNodeWithContentDescription("PDF sayfası " + page).performTouchInput {
            down(Offset(width * fraction, height * .5f)); advanceEventTime(32); up()
        }
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
    }
    @Test fun singlePageUsesViewportThirdsAndOffRestoresOriginalTap() {
        val f = show(false)
        tap(1, .1f); tap(1, .5f); tap(1, .9f)
        assertEquals(listOf(TapZone.LEFT, TapZone.MIDDLE, TapZone.RIGHT), f.taps.map { it.zone })
        assertEquals(0, f.fallback)
        f.handled = false; tap(1, .9f); assertEquals(1, f.fallback)
    }
    @Test fun twoPagesShareOneViewportWithBothInnerEdgesInMiddleZone() {
        val f = show(true)
        tap(1, .1f); tap(1, .9f); tap(2, .1f); tap(2, .9f)
        assertEquals(listOf(TapZone.LEFT, TapZone.MIDDLE, TapZone.MIDDLE, TapZone.RIGHT), f.taps.map { it.zone })
        assertEquals(0, f.fallback); assertEquals(0, f.selections)
    }
    @Test fun selectionSwipeDoubleTapMultitouchAndCancellationDoNotTriggerZoneActions() {
        val f = show(false)
        val node = compose.onNodeWithContentDescription("PDF sayfası 1")
        node.performTouchInput { down(center); advanceEventTime(650); up() }
        assertEquals(1, f.selections)
        node.performTouchInput { down(center + Offset(100f, 0f)); moveTo(center - Offset(100f, 0f)); up() }
        compose.waitForIdle(); assertEquals(listOf(1), f.swipes)
        node.performTouchInput { doubleClick(center) }
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle(); assertTrue(f.taps.isEmpty())
        node.performTouchInput { down(center); cancel() }
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle(); assertTrue(f.taps.isEmpty())
        node.performTouchInput { down(0, center); down(1, center + Offset(20f, 0f)); up(1); up(0) }
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        assertTrue(f.taps.isEmpty()); assertEquals(0, f.fallback)
        tap(1, .9f); assertEquals(TapZone.RIGHT, f.taps.single().zone)
    }
}
