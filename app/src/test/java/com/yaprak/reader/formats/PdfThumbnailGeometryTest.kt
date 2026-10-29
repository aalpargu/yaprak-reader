package com.yaprak.reader.formats

import com.yaprak.reader.formats.pdf.*
import org.junit.Assert.*
import org.junit.Test

class PdfThumbnailGeometryTest {
    @Test fun `portrait landscape and square pages preserve aspect inside a fixed box`() {
        assertEquals(240 to 310, pdfThumbnailSize(612, 792))
        assertEquals(240 to 185, pdfThumbnailSize(792, 612))
        assertEquals(240 to 240, pdfThumbnailSize(100, 100))
        assertEquals(120 to 320, pdfThumbnailSize(3, 8))
    }

    @Test fun `small vector pages are enlarged without imposing raster cover semantics`() {
        assertEquals(240 to 320, pdfThumbnailSize(30, 40))
        assertEquals(240 to 120, pdfThumbnailSize(2, 1))
    }

    @Test fun `pathological source dimensions cannot overflow allocation bounds`() {
        for ((width, height) in listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1,
            Int.MAX_VALUE to Int.MAX_VALUE, 1 to 1, 16_384 to 4096)) {
            val (w, h) = pdfThumbnailSize(width, height)
            assertTrue(w in 1..PDF_THUMBNAIL_WIDTH)
            assertTrue(h in 1..PDF_THUMBNAIL_HEIGHT)
            assertTrue(w.toLong() * h * 4 <= MAX_PDF_THUMBNAIL_BYTES)
        }
        assertEquals(1 to 320, pdfThumbnailSize(1, Int.MAX_VALUE))
        assertEquals(240 to 1, pdfThumbnailSize(Int.MAX_VALUE, 1))
    }

    @Test fun `zero and negative native dimensions are rejected before allocation`() {
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 5, 5 to Int.MIN_VALUE)) {
            assertTrue(runCatching { pdfThumbnailSize(width, height) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
