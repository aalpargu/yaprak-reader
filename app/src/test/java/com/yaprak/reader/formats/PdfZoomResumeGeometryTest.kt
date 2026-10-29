package com.yaprak.reader.formats

import android.graphics.RectF
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
class PdfZoomResumeGeometryTest {
    @Test fun actualZoomedAndPannedReadingLineMapsToTheOriginalPageY() {
        val geometry = PdfDrawGeometry(IntSize(240, 600), IntSize(600, 1600), PdfPageCrop(), PdfFitMode.WIDTH, 2f, Offset.Zero)
        // Painted Y is -300..980 at 2x, then -360..920 after a 60px upward pan.
        assertEquals(271_875, geometry.resumeAnchorY(48f))
        assertEquals(318_750, geometry.copy(pan = Offset(20f, -60f)).resumeAnchorY(48f))
    }
    @Test fun aCroppedZoomedPointReopensAtBaselineUnderDifferentWidthAndCrop() {
        val transformed = PdfDrawGeometry(IntSize(400, 1400), IntSize(600, 2400), PdfPageCrop(10, 15),
            PdfFitMode.WIDTH, 2f, Offset(123f, -100f))
        // Original crop Y=360..2040 paints at -800..2000. Local Y=400 is original Y=1080.
        val saved = transformed.resumeAnchorY(400f)
        assertEquals(450_000, saved)
        val source = PdfPageDimensions(600, 2400); val nextCrop = PdfPageCrop(0, 20)
        val height = requireNotNull(pdfScrollItemHeight(source, 240, 700, 96, nextCrop, PdfFitMode.WIDTH))
        val offset = requireNotNull(pdfResumeScrollOffset(requireNotNull(saved), source, 240, 700, 96, 48, nextCrop, PdfFitMode.WIDTH))
        val reopened = PdfDrawGeometry(IntSize(240, height), IntSize(600, 2400), nextCrop, PdfFitMode.WIDTH, 1f, Offset.Zero)
        assertEquals(450_000, reopened.resumeAnchorY(offset + 48f))
    }
    @Test fun finiteInputsWhoseTransformOverflowsCannotOfferAPaintedRectangleOrPoint() {
        val geometry = PdfDrawGeometry(IntSize(240, 600), IntSize(600, 1600), PdfPageCrop(),
            PdfFitMode.WIDTH, Float.MAX_VALUE, Offset.Zero)
        assertFalse(geometry.destination(RectF()))
        assertNull(geometry.resumeAnchorY(48f))
        // Each edge is finite here, but the rectangle width/height overflow on subtraction.
        val wide = geometry.copy(viewport = IntSize(1_000_000, 1_000_000), source = IntSize(1, 1), zoom = 6e32f)
        assertFalse(wide.destination(RectF())); assertNull(wide.resumeAnchorY(48f))
    }
    @Test fun invalidSourceViewportTransformAndReadingLineStillRefuseAnAnchor() {
        val geometry = PdfDrawGeometry(IntSize(240, 600), IntSize(600, 1600), PdfPageCrop(), PdfFitMode.WIDTH, 2f, Offset.Zero)
        for (bad in listOf(geometry.copy(zoom = 0f), geometry.copy(zoom = Float.NaN), geometry.copy(zoom = Float.POSITIVE_INFINITY),
            geometry.copy(pan = Offset(Float.NaN, 0f)), geometry.copy(pan = Offset(0f, Float.POSITIVE_INFINITY)),
            geometry.copy(viewport = IntSize.Zero), geometry.copy(source = IntSize.Zero))) assertNull(bad.resumeAnchorY(48f))
        assertNull(geometry.resumeAnchorY(Float.NaN)); assertNull(geometry.resumeAnchorY(Float.POSITIVE_INFINITY))
    }
    @Test fun transformedPointReflowResetsOnlyItsOwnerAndRequiresTheNewRevisionBeforePaint() {
        val viewport = PdfScrollViewport(400, 800, 96, 48, PdfPageCrop(), PdfFitMode.WIDTH)
        val status = PdfRenderStatus(4, 1)
        status.setScrolling(true); status.updateScrollGeometry(viewport); status.registerScrollItem(1); status.registerScrollItem(2)
        status.updateScrollViewport(listOf(1, 2), 1)
        val ticket = requireNotNull(status.startRender(1)); status.finishRender(1, ticket)
        status.finishDraw(1, ticket, 318_750, pointRequiresBaselineReset = true)
        val previous = status.scrollNavigation.value
        status.updateScrollGeometry(viewport.copy(height = 600))
        val next = status.scrollNavigation.value
        assertEquals(318_750, next.anchorY); assertTrue(next.revision > previous.revision)
        assertEquals(next.revision, next.pointRevision(1)); assertEquals(0L, next.pointRevision(2))
        assertNull(status.drawnPosition.value)
        status.updateScrollViewport(listOf(1, 2), 1, previous.revision)
        status.finishDraw(1, ticket, 123_000, pointRequiresBaselineReset = true)
        assertNull(status.drawnPosition.value)
        status.updateScrollViewport(listOf(1, 2), 1, next.revision)
        assertNull(status.drawnPosition.value)
        status.finishDraw(1, ticket, 318_750)
        assertEquals(PdfResumePosition(1, 318_750), status.drawnPosition.value)
        status.updateScrollGeometry(viewport.copy(width = 240, height = 600))
        assertEquals(318_750, status.scrollNavigation.value.anchorY)
        assertEquals(next.pointNavigationRevisions, status.scrollNavigation.value.pointNavigationRevisions)
    }
    @Test fun zoomOrPanChangeRevokesThePreviousPaintEvenWithTheSameBitmapAndTicket() {
        val image = android.graphics.Bitmap.createBitmap(24, 60, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val witness = PdfPageDrawWitness {}
            val before = PdfDrawGeometry(IntSize(240, 600), IntSize(600, 1600), PdfPageCrop(), PdfFitMode.WIDTH, 1f, Offset.Zero)
            witness.update(image, 1, before); witness.painted(image, 1, before)
            assertEquals(1L, witness.paintedTicket); assertFalse(witness.pointRequiresBaselineReset)
            val zoomed = before.copy(zoom = 2f)
            witness.update(image, 1, zoomed); assertNull(witness.paintedTicket)
            witness.painted(image, 1, before); assertNull(witness.paintedTicket)
            witness.painted(image, 1, zoomed); assertEquals(1L, witness.paintedTicket)
            assertTrue(witness.pointRequiresBaselineReset)
            val panned = zoomed.copy(pan = Offset(20f, -60f))
            witness.update(image, 1, panned); witness.painted(image, 1, zoomed); assertNull(witness.paintedTicket)
            witness.painted(image, 1, panned); assertEquals(1L, witness.paintedTicket)
        } finally { image.recycle() }
    }
}
