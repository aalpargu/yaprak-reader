package com.yaprak.reader.formats

import android.app.Application
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.IntSize
import com.yaprak.reader.formats.pdf.PdfDrawGeometry
import com.yaprak.reader.formats.pdf.PdfFitMode
import com.yaprak.reader.reader.PdfPageCrop
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfDrawGeometryTest {
    @Test fun fittedPageBoundsExcludeTheViewportLetterboxAndUseTheActualCroppedSourceAspect() {
        val out = RectF()
        assertTrue(geometry().destination(out)); assertEquals(RectF(40f, 0f, 200f, 240f), out)
        assertTrue(geometry(crop = PdfPageCrop(0, 20)).destination(out))
        assertEquals(RectF(0f, 12f, 240f, 228f), out)
    }
    @Test fun widthFitTopAndCenterScalePanBoundsMatchTheRealPainterTransform() {
        val out = RectF()
        assertTrue(geometry(fit = PdfFitMode.WIDTH).destination(out)); assertEquals(RectF(0f, 0f, 240f, 360f), out)
        assertTrue(geometry(fit = PdfFitMode.WIDTH, zoom = 2f, pan = Offset(20f, -30f)).destination(out))
        assertEquals(RectF(-100f, -150f, 380f, 570f), out)
        assertTrue(geometry(zoom = 2f, pan = Offset(10f, 15f)).destination(out))
        assertEquals(RectF(-30f, -105f, 290f, 375f), out)
    }
    @Test fun emptyInvalidOrNonFiniteGeometryCannotOfferAVisibleImageRectangle() {
        val out = RectF()
        assertFalse(geometry().copy(viewport = IntSize.Zero).destination(out))
        assertFalse(geometry().copy(source = IntSize(0, 120)).destination(out))
        for (zoom in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) assertFalse(geometry(zoom = zoom).destination(out))
        for (pan in listOf(Offset(Float.NaN, 0f), Offset(0f, Float.NEGATIVE_INFINITY))) assertFalse(geometry(pan = pan).destination(out))
    }
    @Test fun newPointRevisionRejectsThePreviousPaintEvenWhenPixelsAndRenderTicketMatch() {
        val bitmap = android.graphics.Bitmap.createBitmap(80, 120, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val witness = com.yaprak.reader.formats.pdf.PdfPageDrawWitness {}
            val before = geometry().copy(pointNavigationRevision = 5)
            witness.update(bitmap, 10, before)
            assertNull(witness.paintedTicket)
            witness.painted(bitmap, 10, before); assertEquals(10L, witness.paintedTicket)
            val next = before.copy(pointNavigationRevision = 6)
            witness.update(bitmap, 10, next); assertNull(witness.paintedTicket)
            witness.painted(bitmap, 10, before); assertNull(witness.paintedTicket)
            witness.painted(bitmap, 10, next); assertEquals(10L, witness.paintedTicket)
            assertEquals(6L, witness.pointNavigationRevision)
        } finally { bitmap.recycle() }
    }


    @Test fun scrollingBehindTheSameClippedViewportRevokesThePreviousReadingLine() {
        val root = PlacedCoordinates(IntSize(1080, 1920))
        val page = PlacedCoordinates(IntSize(1080, 8640), root)
        var invalidations = 0
        val witness = com.yaprak.reader.formats.pdf.PdfPageDrawWitness { invalidations++ }
        val clipped = page.boundsInWindow()
        witness.position(page)
        assertEquals(1, invalidations)
        page.windowOrigin = Offset(0f, -747f)
        assertEquals("The same viewport can conceal a changed original reading line", clipped, page.boundsInWindow())
        witness.position(page)
        assertEquals(2, invalidations)
        witness.position(page)
        assertEquals("An unchanged placement must not schedule another host draw", 2, invalidations)
    }

    @Test fun clippingAndDetachmentStillRevokeAReadingLineWithoutAnOriginChange() {
        val root = PlacedCoordinates(IntSize(1080, 1920))
        val page = PlacedCoordinates(IntSize(1080, 8640), root)
        var invalidations = 0
        val witness = com.yaprak.reader.formats.pdf.PdfPageDrawWitness { invalidations++ }
        witness.position(page)
        root.size = IntSize(1080, 900)
        witness.position(page)
        assertEquals(2, invalidations)
        page.attached = false
        witness.position(page)
        assertEquals(3, invalidations)
        witness.position(page)
        assertEquals(3, invalidations)
        page.attached = true
        witness.position(page)
        assertEquals(4, invalidations)
    }

    /** Coordinate-only fixture; it never acknowledges bitmap paint or reader persistence. */
    private class PlacedCoordinates(
        override var size: IntSize,
        override val parentLayoutCoordinates: LayoutCoordinates? = null,
    ) : LayoutCoordinates by (Proxy.newProxyInstance(LayoutCoordinates::class.java.classLoader,
        arrayOf(LayoutCoordinates::class.java)) { _, method, _ ->
        error("Unexpected coordinate query: ${method.name}")
    } as LayoutCoordinates) {
        var windowOrigin = Offset.Zero
        var attached = true
        override val isAttached get() = attached
        override val parentCoordinates get() = parentLayoutCoordinates
        override fun localToWindow(relativeToLocal: Offset) = windowOrigin + relativeToLocal
        override fun localBoundingBoxOf(sourceCoordinates: LayoutCoordinates, clipBounds: Boolean): Rect {
            val source = sourceCoordinates as PlacedCoordinates
            val origin = source.windowOrigin - windowOrigin
            val rectangle = Rect(origin.x, origin.y, origin.x + source.size.width, origin.y + source.size.height)
            return if (clipBounds) rectangle.intersect(Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())) else rectangle
        }
    }

    private fun geometry(crop: PdfPageCrop = PdfPageCrop(), fit: PdfFitMode = PdfFitMode.PAGE,
        zoom: Float = 1f, pan: Offset = Offset.Zero) = PdfDrawGeometry(IntSize(240, 240), IntSize(80, 120), crop, fit, zoom, pan)
}
