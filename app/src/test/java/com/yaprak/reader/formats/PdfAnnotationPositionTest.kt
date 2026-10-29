package com.yaprak.reader.formats

import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.*
import org.junit.Assert.*
import org.junit.Test

class PdfAnnotationPositionTest {
    private fun anchor(page: Int = 2, height: Int = 1200, bounds: List<PdfTextBounds> =
        listOf(PdfTextBounds(10.0, 840.0, 100.0, 860.0), PdfTextBounds(10.0, 720.0, 100.0, 740.0))) =
        PdfSourceAnchor(page, 200, height, PdfPagePoint(100, height - 1), PdfPagePoint(10, 0), bounds)

    @Test fun reversedSelectionAndUnorderedNativeBoundsUseTheEarliestOriginalLine() {
        assertEquals(PdfResumePosition(2, 600_000), pdfAnnotationPosition(anchor(), 3))
        val source = anchor(bounds = listOf(PdfTextBounds(10.0, 720.0, 100.0, 740.0), PdfTextBounds(10.0, 840.0, 100.0, 860.0)))
        assertEquals(PdfResumePosition(2, 600_000), pdfAnnotationPosition(source, 3))
    }
    @Test fun sourceCoordinatesUseBoundedFloorPrecisionWithoutPixelOrCropDependencies() {
        assertEquals(PdfResumePosition(2, 0), pdfAnnotationPosition(anchor(height = 3,
            bounds = listOf(PdfTextBounds(10.0, 0.0, 100.0, 1.0))), 3))
        assertEquals(PdfResumePosition(2, 333_333), pdfAnnotationPosition(anchor(height = 3,
            bounds = listOf(PdfTextBounds(10.0, 1.0, 100.0, 2.0))), 3))
        val height = Int.MAX_VALUE
        val result = pdfAnnotationPosition(anchor(height = height,
            bounds = listOf(PdfTextBounds(10.0, height - 1.0, 100.0, height.toDouble()))), 3)!!
        assertEquals(999_999, result.anchorY)
    }
    @Test fun foreignOrUnavailableDocumentPageCountsCannotProduceATarget() {
        assertNull(pdfAnnotationPosition(anchor(), 2)); assertNull(pdfAnnotationPosition(anchor(), 0))
        assertNull(pdfAnnotationPosition(anchor(), -1)); assertEquals(2, pdfAnnotationPosition(anchor(), 3)!!.pageIndex)
    }
}
