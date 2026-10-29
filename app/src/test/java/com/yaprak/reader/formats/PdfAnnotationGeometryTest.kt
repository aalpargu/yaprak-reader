package com.yaprak.reader.formats

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.*
import org.junit.Assert.*
import org.junit.Test

class PdfAnnotationGeometryTest {
    private val bounds=PdfTextBounds(60.0,90.0,180.0,110.0)
    private val anchor=PdfSourceAnchor(3,300,600,PdfPagePoint(60,100),PdfPagePoint(180,100),listOf(bounds))
    @Test fun pageFitUsesNativePointDimensionsAndTheRealBitmapScale() {
        assertEquals(Rect(80f,120f,240f,146.66667f),pdfAnnotationRectangle(anchor,bounds,IntSize(400,800),IntSize(600,1200),PdfPageCrop(),PdfFitMode.PAGE))
    }
    @Test fun landscapeGreyMarginsAndWidthFitUseTheSameOriginsAsTextSelection() {
        val centered=pdfAnnotationRectangle(anchor,bounds,IntSize(400,400),IntSize(300,600),PdfPageCrop(),PdfFitMode.PAGE)!!
        assertEquals(140f,centered.left,0.0001f); assertEquals(60f,centered.top,0.0001f)
        val width=pdfAnnotationRectangle(anchor,bounds,IntSize(400,300),IntSize(300,600),PdfPageCrop(),PdfFitMode.WIDTH)!!
        assertEquals(80f,width.left,0.0001f); assertEquals(120f,width.top,0.0001f)
    }
    @Test fun cropClipsOnlyExistingNativeBoundsAndNeverMovesOffPageTextIntoTheVisibleArea() {
        val crop=PdfPageCrop(20,20)
        assertNull(pdfAnnotationRectangle(anchor,bounds,IntSize(400,800),IntSize(300,600),crop,PdfFitMode.PAGE))
        val crossing=PdfTextBounds(30.0,90.0,180.0,200.0)
        val evidence=PdfSourceAnchor(3,300,600,anchor.start,anchor.stop,listOf(crossing))
        val actual=pdfAnnotationRectangle(evidence,crossing,IntSize(400,800),IntSize(300,600),crop,PdfFitMode.PAGE)!!
        assertEquals(0f,actual.left,0.0001f); assertEquals(0f,actual.top,0.0001f); assertEquals(266.66666f,actual.right,0.0001f)
    }
    @Test fun sharedLayerZoomAndPanRoundTripToTheSameUncroppedNativePoints() {
        val viewport=IntSize(400,800); val image=IntSize(600,1200); val crop=PdfPageCrop(10,10)
        val rect=pdfAnnotationRectangle(anchor,bounds,viewport,image,crop,PdfFitMode.PAGE)!!
        val zoom=2f; val pan=Offset(10f,300f)
        fun transformed(point: Offset)=Offset((point.x-viewport.width/2f)*zoom+viewport.width/2f+pan.x,(point.y-viewport.height/2f)*zoom+viewport.height/2f+pan.y)
        val source=pdfSelectionPoint(transformed(rect.center),viewport,image,crop,PdfFitMode.PAGE,zoom,pan)!!
        assertEquals(0.4,source.x,0.000001); assertEquals(100.0/600,source.y,0.000001)
    }
    @Test fun absentViewportAndAnUnownedRectangleCannotBecomeAnnotationPaint() {
        assertNull(pdfAnnotationRectangle(anchor,bounds,IntSize.Zero,IntSize(300,600),PdfPageCrop(),PdfFitMode.PAGE))
        assertNull(pdfAnnotationRectangle(anchor,PdfTextBounds(0.0,0.0,10.0,10.0),IntSize(400,800),IntSize(300,600),PdfPageCrop(),PdfFitMode.PAGE))
    }
}
