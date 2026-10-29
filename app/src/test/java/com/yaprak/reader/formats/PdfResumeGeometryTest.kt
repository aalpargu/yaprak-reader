package com.yaprak.reader.formats

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.PdfPageCrop
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfResumeGeometryTest {
    @Test fun originalCoordinateRoundTripsAcrossWidthsAndCropsAtTheSameReadingLine() {
        val source = PdfPageDimensions(600, 2400)
        for (width in listOf(240, 400, 800)) for (crop in listOf(PdfPageCrop(), PdfPageCrop(10, 15))) {
            val height = pdfScrollItemHeight(source, width, 700, 96, crop, PdfFitMode.WIDTH)!!
            val offset = pdfResumeScrollOffset(600_000, source, width, 700, 96, 48, crop, PdfFitMode.WIDTH)!!
            val actual = PdfDrawGeometry(IntSize(width, height), IntSize(source.width, source.height),
                crop, PdfFitMode.WIDTH, 1f, Offset.Zero).resumeAnchorY(offset + 48f)!!
            assertEquals(600_000.0, actual.toDouble(), 1600.0)
        }
    }
    @Test fun pageFitLetterboxingAndShortMinimumHeightUseActualPaintedImage() {
        val source = PdfPageDimensions(1000, 50)
        val crop = PdfPageCrop()
        val height = pdfScrollItemHeight(source, 400, 700, 96, crop, PdfFitMode.PAGE)!!
        assertEquals(96, height)
        val geometry = PdfDrawGeometry(IntSize(400, height), IntSize(1000, 50), crop, PdfFitMode.PAGE, 1f, Offset.Zero)
        assertEquals(500_000, geometry.resumeAnchorY(48f))
        assertEquals(0, geometry.resumeAnchorY(0f))
        assertEquals(1_000_000, geometry.resumeAnchorY(96f))
        assertEquals(0, pdfResumeScrollOffset(500_000, source, 400, 700, 96, 48, crop, PdfFitMode.PAGE))
    }
    @Test fun anchorsOutsideTheCurrentCropAndDocumentTopClampToReachablePaintedGeometry() {
        val source = PdfPageDimensions(600, 2400)
        val crop = PdfPageCrop(0, 20)
        val height = pdfScrollItemHeight(source, 400, 700, 96, crop, PdfFitMode.WIDTH)!!
        val geometry = PdfDrawGeometry(IntSize(400, height), IntSize(600, 2400), crop, PdfFitMode.WIDTH, 1f, Offset.Zero)
        assertEquals(200_000, geometry.resumeAnchorY(-20f))
        assertEquals(800_000, geometry.resumeAnchorY(height + 20f))
        assertEquals(0, pdfResumeScrollOffset(0, source, 400, 700, 96, 48, crop, PdfFitMode.WIDTH))
        assertEquals(height - 48, pdfResumeScrollOffset(1_000_000, source, 400, 700, 96, 48, crop, PdfFitMode.WIDTH))
    }
    @Test fun tinyViewportUsesTheSameReadingInsetAsPrimaryPageSelection() {
        val source = PdfPageDimensions(400, 2000)
        assertEquals(980, pdfResumeScrollOffset(500_000, source, 400, 100, 96, 48, PdfPageCrop(), PdfFitMode.WIDTH))
    }
    @Test fun invalidDimensionsCoordinatesAndNonFiniteTransformsCannotGenerateAnAnchor() {
        val geometry = PdfDrawGeometry(IntSize(400, 1600), IntSize(600, 2400), PdfPageCrop(), PdfFitMode.WIDTH, 1f, Offset.Zero)
        assertNull(geometry.resumeAnchorY(Float.NaN))
        assertNull(geometry.copy(zoom = Float.NaN).resumeAnchorY(48f))
        assertNull(geometry.copy(pan = Offset(0f, Float.POSITIVE_INFINITY)).resumeAnchorY(48f))
        assertNull(geometry.copy(viewport = IntSize.Zero).resumeAnchorY(48f))
        for (anchor in listOf(-1, 1_000_001)) assertNull(pdfResumeScrollOffset(anchor, PdfPageDimensions(600, 2400), 400, 700, 96, 48, PdfPageCrop(), PdfFitMode.WIDTH))
        assertNull(pdfResumeScrollOffset(500_000, PdfPageDimensions(600, 2400), 0, 700, 96, 48, PdfPageCrop(), PdfFitMode.WIDTH))
        assertNull(pdfResumeScrollOffset(500_000, PdfPageDimensions(1, 1_000_000), 400, 700, 96, 48, PdfPageCrop(), PdfFitMode.WIDTH))
    }
}
