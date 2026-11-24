package com.yaprak.reader.formats.pdf.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.domain.pdfink.PdfInkMode
import com.yaprak.reader.domain.pdfink.PdfInkPoint
import com.yaprak.reader.domain.pdfink.PdfInkSegment
import com.yaprak.reader.domain.pdfink.PdfInkStroke
import com.yaprak.reader.formats.pdf.PdfFitMode
import com.yaprak.reader.formats.pdf.pdfSelectionPoint
import com.yaprak.reader.reader.PdfPageCrop
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/** Ink is stored in page units; every on-screen geometry must map back to the same page point. */
class PdfInkProjectionTest {
    private val viewport = IntSize(1080, 1920)
    private val crops = listOf(PdfPageCrop(), PdfPageCrop(10, 5), PdfPageCrop(20, 20))
    private val images = listOf(IntSize(1080, 1397), IntSize(2160, 2794), IntSize(1400, 900))

    /** Forward graphicsLayer transform (scale around the box center, then translate). */
    private fun screen(canvas: Offset, zoom: Float, pan: Offset) = Offset(
        (canvas.x - viewport.width / 2f) * zoom + viewport.width / 2f + pan.x,
        (canvas.y - viewport.height / 2f) * zoom + viewport.height / 2f + pan.y)

    @Test fun pagePointsSurviveZoomPanCropFitAndRenderWidth() {
        val point = PdfInkPoint(41_234, 63_210)
        for (image in images) for (crop in crops) for (mode in PdfFitMode.entries) for (zoom in listOf(1f, 1.7f, 3f)) {
            val projection = requireNotNull(PdfInkProjection.of(viewport, image, crop, mode))
            val pan = Offset(-37f * zoom, 120f * (zoom - 1))
            val touch = screen(projection.toCanvas(point), zoom, pan)
            val back = projection.touchToPage(touch, zoom, pan)
            assertTrue("$image $crop $mode $zoom $back", abs(back.x - point.x) <= 2 && abs(back.y - point.y) <= 2)
        }
    }

    @Test fun agreesWithTheTextSelectionInverse() {
        for (crop in crops) for (mode in PdfFitMode.entries) {
            val image = images[0]
            val projection = requireNotNull(PdfInkProjection.of(viewport, image, crop, mode))
            val touch = Offset(500f, 700f); val zoom = 2f; val pan = Offset(30f, -40f)
            val selection = pdfSelectionPoint(touch, viewport, image, crop, mode, zoom, pan) ?: continue
            val ink = projection.touchToPage(touch, zoom, pan)
            assertEquals(selection.x * 100_000, ink.x.toDouble(), 1.0)
            assertEquals(selection.y * 100_000, ink.y.toDouble(), 1.0)
        }
    }

    @Test fun pointsOutsideTheVisiblePageClampOntoIt() {
        val projection = requireNotNull(PdfInkProjection.of(viewport, IntSize(1000, 1000), PdfPageCrop(20, 20), PdfFitMode.PAGE))
        val corner = projection.toPage(Offset(-500f, -500f))
        assertEquals(PdfInkPoint(20_000, 20_000), corner)
        val far = projection.toPage(Offset(5000f, 9000f))
        assertEquals(PdfInkPoint(80_000, 80_000), far)
    }

    @Test fun widthIsAFractionOfThePageHeightAtEveryRenderSize() {
        val small = requireNotNull(PdfInkProjection.of(viewport, IntSize(1080, 1397), PdfPageCrop(), PdfFitMode.WIDTH))
        val large = requireNotNull(PdfInkProjection.of(viewport, IntSize(2160, 2794), PdfPageCrop(), PdfFitMode.WIDTH))
        // Fit-width: the page is 1080 px wide on screen, so 1397 px tall; 2% of that is ~27.9 px.
        assertEquals(27.94f, small.widthPx(2000), 0.05f)
        assertEquals(small.widthPx(2000), large.widthPx(2000), 0.05f)
    }

    @Test fun hitTestingFollowsTheBandNotItsBoundingBox() {
        val projection = requireNotNull(PdfInkProjection.of(viewport, IntSize(1000, 1000), PdfPageCrop(), PdfFitMode.WIDTH))
        // Diagonal band from (10%,10%) to (90%,90%), 2% of the page height thick (~21.6 px here).
        val stroke = PdfInkStroke("s", 0, PdfHighlightColor.YELLOW, PdfInkMode.FREEHAND,
            listOf(PdfInkSegment(2000, listOf(PdfInkPoint(10_000, 10_000), PdfInkPoint(90_000, 90_000)))), 0)
        val middle = projection.toCanvas(PdfInkPoint(50_000, 50_000))
        assertTrue(projection.hits(stroke, middle, 0f))
        assertTrue(projection.hits(stroke, middle + Offset(5f, -5f), 0f))
        assertFalse(projection.hits(stroke, middle + Offset(12f, -12f), 0f))
        // Inside the bounding box, far from the diagonal.
        assertFalse(projection.hits(stroke, projection.toCanvas(PdfInkPoint(80_000, 20_000)), 8f))
    }

    @Test fun alignedBandEndsArePulledInsideTheWords() {
        val projection = requireNotNull(PdfInkProjection.of(viewport, IntSize(1000, 1000), PdfPageCrop(), PdfFitMode.WIDTH))
        val segment = PdfInkSegment(2000, listOf(PdfInkPoint(10_000, 50_000), PdfInkPoint(60_000, 50_000)))
        val (a, b) = pdfInkCanvasPoints(segment, PdfInkMode.ALIGNED, projection)
        val width = projection.widthPx(2000)
        assertEquals(projection.toCanvas(segment.points[0]).x + width * .3f, a.x, .01f)
        assertEquals(projection.toCanvas(segment.points[1]).x - width * .3f, b.x, .01f)
        // A single short word never turns inside out.
        val word = PdfInkSegment(4000, listOf(PdfInkPoint(10_000, 50_000), PdfInkPoint(11_000, 50_000)))
        val (c, d) = pdfInkCanvasPoints(word, PdfInkMode.ALIGNED, projection)
        assertEquals(c.x, d.x, .01f)
        // Freehand points are untouched.
        assertEquals(segment.points.map(projection::toCanvas), pdfInkCanvasPoints(segment, PdfInkMode.FREEHAND, projection))
    }

    @Test fun nightAndOldAndroidUseATranslucentNormalBlend() {
        assertEquals(androidx.compose.ui.graphics.BlendMode.Multiply, pdfInkBlend(night = false, sdk = 35).mode)
        assertEquals(androidx.compose.ui.graphics.BlendMode.SrcOver, pdfInkBlend(night = true, sdk = 35).mode)
        assertEquals(androidx.compose.ui.graphics.BlendMode.SrcOver, pdfInkBlend(night = false, sdk = 28).mode)
        assertTrue(pdfInkBlend(night = true).alpha < .5f)
    }

    @Test fun invalidSizesHaveNoProjection() {
        assertNull(PdfInkProjection.of(IntSize.Zero, IntSize(10, 10), PdfPageCrop(), PdfFitMode.PAGE))
        assertNull(PdfInkProjection.of(viewport, IntSize(0, 10), PdfPageCrop(), PdfFitMode.PAGE))
    }
}
