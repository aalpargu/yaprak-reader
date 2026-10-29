package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.yaprak.reader.formats.pdf.PdfCropRequest
import com.yaprak.reader.formats.pdf.PdfPage
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.reader.PdfTextPoint
import com.yaprak.reader.ui.theme.YaprakTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose pointer dispatch and geometry; controlled bitmap, no native decoder claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfTextSelectionGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longPressWithoutDragRequestsOneWordOnTheActualPageAndDoesNotTapOrSwipe() {
        val f = show()
        compose.onNodeWithTag(TAG).performTouchInput {
            down(center); advanceEventTime(650); up()
        }
        compose.waitForIdle()
        val selected = f.selections.single()
        assertEquals(3, selected.page)
        point(.5, .5, selected.start); assertEquals(selected.start, selected.stop)
        assertEquals(0, f.taps); assertTrue(f.swipes.isEmpty())
    }

    @Test fun dragAfterLongPressSelectsARangeWithoutPanningOrTurningThePage() {
        val f = show()
        compose.onNodeWithTag(TAG).performTouchInput {
            down(Offset(60f, 80f)); advanceEventTime(650)
            moveTo(Offset(120f, 100f)); moveTo(Offset(180f, 140f)); up()
        }
        compose.waitForIdle()
        val selected = f.selections.single()
        point(.25, 1.0 / 3, selected.start); point(.75, 7.0 / 12, selected.stop)
        assertEquals(0, f.taps); assertTrue(f.swipes.isEmpty())
        // A second completed gesture proves the first selection did not move the bitmap.
        compose.onNodeWithTag(TAG).performTouchInput { down(center); advanceEventTime(650); up() }
        point(.5, .5, f.selections.last().start)
    }

    @Test fun greyMarginsAndDragLeavingThePageCannotSelectTheNearestWord() {
        val f = show(height = 480)
        compose.onNodeWithTag(TAG).performTouchInput { down(Offset(20f, 120f)); advanceEventTime(650); up() }
        compose.onNodeWithTag(TAG).performTouchInput {
            down(center); advanceEventTime(650); moveTo(Offset(10f, 120f)); moveTo(center); up()
        }
        compose.waitForIdle()
        assertTrue(f.selections.isEmpty()); assertTrue(f.swipes.isEmpty()); assertEquals(0, f.taps)
    }

    @Test fun croppedImageCoordinatesRemainCoordinatesOfTheOriginalPdfPage() {
        val f = show(crop = PdfPageCrop(10, 20))
        compose.onNodeWithTag(TAG).performTouchInput {
            down(Offset(60f, 90f)); advanceEventTime(650); moveTo(Offset(180f, 150f)); up()
        }
        compose.waitForIdle()
        val selected = f.selections.single()
        point(.3, .4, selected.start); point(.7, .6, selected.stop)
    }

    @Test fun zoomedImageUsesTheInverseTransformAndKeepsZoomAfterSelection() {
        val f = show()
        compose.onNodeWithTag(TAG).performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        compose.onNodeWithTag(TAG).performTouchInput {
            down(Offset(60f, 90f)); advanceEventTime(650); moveTo(Offset(180f, 150f)); up()
        }
        compose.waitForIdle()
        point(.375, .4375, f.selections.single().start)
        point(.625, .5625, f.selections.single().stop)
        compose.onNodeWithTag(TAG).performTouchInput { down(Offset(60f, 90f)); advanceEventTime(650); up() }
        point(.375, .4375, f.selections.last().start)
        assertTrue(f.swipes.isEmpty())
    }

    @Test fun cancellationAndSecondPointerInvalidateTheWholeLongPress() {
        val f = show()
        compose.onNodeWithTag(TAG).performTouchInput {
            down(center); advanceEventTime(650); moveTo(center + Offset(30f, 0f)); cancel()
        }
        compose.onNodeWithTag(TAG).performTouchInput {
            down(0, center); down(1, center + Offset(20f, 0f)); advanceEventTime(650)
            up(1); moveTo(0, center + Offset(50f, 0f)); up(0)
        }
        compose.onNodeWithTag(TAG).performTouchInput {
            down(0, center); advanceEventTime(650); down(1, center + Offset(20f, 0f))
            up(1); moveTo(0, center + Offset(50f, 0f)); up(0)
        }
        compose.waitForIdle()
        assertTrue(f.selections.isEmpty()); assertTrue(f.swipes.isEmpty()); assertEquals(0, f.taps)
    }

    @Test fun changingCropWhilePressedDropsTheOldGestureAndTheNewGestureUsesNewGeometry() {
        val f = show()
        compose.onNodeWithTag(TAG).performTouchInput { down(Offset(60f, 90f)); advanceEventTime(650) }
        compose.runOnIdle { f.crop = PdfCropRequest(PdfPageCrop(10, 20), 1) }
        compose.onNodeWithTag(TAG).performTouchInput { moveTo(Offset(180f, 150f)); up() }
        compose.waitForIdle(); assertTrue(f.selections.isEmpty())
        compose.onNodeWithTag(TAG).performTouchInput { down(Offset(60f, 90f)); advanceEventTime(650); up() }
        compose.waitForIdle(); point(.3, .4, f.selections.single().start)
    }

    @Test fun enablingSelectionPreservesOrdinarySwipeAndShortTap() {
        val f = show()
        compose.onNodeWithTag(TAG).performTouchInput {
            down(center + Offset(80f, 0f)); moveTo(center + Offset(-80f, 0f)); up()
        }
        compose.waitForIdle()
        assertEquals(listOf(1), f.swipes); assertTrue(f.selections.isEmpty())
        compose.onNodeWithTag(TAG).performTouchInput { down(center); advanceEventTime(32); up() }
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        assertEquals(1, f.taps); assertTrue(f.selections.isEmpty())
    }

    private data class Selection(val page: Int, val start: PdfTextPoint, val stop: PdfTextPoint)
    private class Fixture(crop: PdfPageCrop) {
        var crop by mutableStateOf(PdfCropRequest(crop))
        val selections = mutableListOf<Selection>()
        val swipes = mutableListOf<Int>()
        var taps = 0
        var rendered = false
    }
    private fun show(height: Int = 240, crop: PdfPageCrop = PdfPageCrop()): Fixture {
        val f = Fixture(crop)
        val bitmap = Bitmap.createBitmap(240, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        compose.setContent { YaprakTheme {
            Box(Modifier.size(240.dp).testTag(TAG)) {
                PdfPage({ _, _ -> bitmap }, 3, { f.taps++ }, { 1L }, { _, _ -> f.rendered = true },
                    onSwipePage = { f.swipes += it; true }, cropRequest = f.crop, applySystemBarsPadding = false,
                    onTextSelection = { page, start, stop -> f.selections += Selection(page, start, stop) })
            }
        } }
        compose.waitUntil(5_000) { f.rendered }; compose.waitForIdle()
        return f
    }
    private fun point(x: Double, y: Double, actual: PdfTextPoint) {
        assertEquals(x, actual.x, .0001); assertEquals(y, actual.y, .0001)
    }
    private companion object { const val TAG = "pdf-native-selection-viewport" }
}
