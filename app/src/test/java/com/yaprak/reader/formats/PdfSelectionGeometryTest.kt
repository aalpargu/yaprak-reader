package com.yaprak.reader.formats

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.PdfPageCrop
import org.junit.Assert.*
import org.junit.Test

class PdfSelectionGeometryTest {
    @Test fun pageFitMapsExactOriginalCornersAndCenter() {
        val viewport = IntSize(400, 800); val image = IntSize(300, 600)
        assertPoint(0.0, 0.0, Offset.Zero, viewport, image)
        assertPoint(1.0, 1.0, Offset(400f, 800f), viewport, image)
        assertPoint(0.5, 0.5, Offset(200f, 400f), viewport, image)
    }
    @Test fun greyMarginsDoNotBecomeNearestWordSelections() {
        val viewport = IntSize(400, 400); val image = IntSize(100, 200)
        assertNull(pdfSelectionPoint(Offset(99f, 200f), viewport, image, PdfPageCrop(), PdfFitMode.PAGE, 1f, Offset.Zero))
        assertNull(pdfSelectionPoint(Offset(301f, 200f), viewport, image, PdfPageCrop(), PdfFitMode.PAGE, 1f, Offset.Zero))
        assertPoint(0.0, 0.0, Offset(100f, 0f), viewport, image)
        assertPoint(1.0, 1.0, Offset(300f, 400f), viewport, image)
    }
    @Test fun widthFitUsesTopInsteadOfCenteredTallPage() {
        val point = pdfSelectionPoint(Offset(200f, 150f), IntSize(400, 300), IntSize(200, 600),
            PdfPageCrop(), PdfFitMode.WIDTH, 1f, Offset.Zero)!!
        assertEquals(0.5, point.x, 1e-9); assertEquals(0.125, point.y, 1e-9)
    }
    @Test fun cropFractionsReturnUncroppedNativePageCoordinates() {
        val crop = PdfPageCrop(10, 20)
        val start = pdfSelectionPoint(Offset.Zero, IntSize(800, 600), IntSize(1000, 1000), crop, PdfFitMode.PAGE, 1f, Offset.Zero)!!
        val stop = pdfSelectionPoint(Offset(800f, 600f), IntSize(800, 600), IntSize(1000, 1000), crop, PdfFitMode.PAGE, 1f, Offset.Zero)!!
        assertEquals(0.1, start.x, 1e-9); assertEquals(0.2, start.y, 1e-9)
        assertEquals(0.9, stop.x, 1e-9); assertEquals(0.8, stop.y, 1e-9)
    }
    @Test fun centeredZoomAndPanInvertToOriginalPoint() {
        val point = pdfSelectionPoint(Offset(20f, 370f), IntSize(200, 400), IntSize(100, 200),
            PdfPageCrop(), PdfFitMode.PAGE, 2f, Offset(20f, -30f))!!
        assertEquals(0.25, point.x, 1e-9); assertEquals(0.75, point.y, 1e-9)
    }
    @Test fun nonFiniteInvalidSizesZoomAndOutOfViewportNeverClampToText() {
        val viewport = IntSize(200, 400); val image = IntSize(100, 200)
        for (touch in listOf(Offset(-1f, 0f), Offset(201f, 0f), Offset(0f, 401f), Offset(Float.NaN, 0f)))
            assertNull(pdfSelectionPoint(touch, viewport, image, PdfPageCrop(), PdfFitMode.PAGE, 1f, Offset.Zero))
        for (zoom in listOf(0f, .5f, 3.1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertNull(pdfSelectionPoint(Offset.Zero, viewport, image, PdfPageCrop(), PdfFitMode.PAGE, zoom, Offset.Zero))
        assertNull(pdfSelectionPoint(Offset.Zero, IntSize.Zero, image, PdfPageCrop(), PdfFitMode.PAGE, 1f, Offset.Zero))
        assertNull(pdfSelectionPoint(Offset.Zero, viewport, IntSize.Zero, PdfPageCrop(), PdfFitMode.PAGE, 1f, Offset.Zero))
        assertNull(pdfSelectionPoint(Offset.Zero, viewport, image, PdfPageCrop(), PdfFitMode.PAGE, 1f, Offset(Float.NaN, 0f)))
    }
    private fun assertPoint(x: Double, y: Double, touch: Offset, viewport: IntSize, image: IntSize) {
        val point = pdfSelectionPoint(touch, viewport, image, PdfPageCrop(), PdfFitMode.PAGE, 1f, Offset.Zero)!!
        assertTrue(point.isValid); assertEquals(x, point.x, 1e-9); assertEquals(y, point.y, 1e-9)
    }
}
