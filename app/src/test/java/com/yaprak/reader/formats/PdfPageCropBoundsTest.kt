package com.yaprak.reader.formats

import com.yaprak.reader.reader.PdfCropArea
import com.yaprak.reader.reader.PdfPageCrop
import org.junit.Assert.*
import org.junit.Test

class PdfPageCropBoundsTest {
    @Test fun `original and independent axes describe exact symmetric source pixels`() {
        assertTrue(PdfPageCrop().isOriginal)
        assertEquals(PdfCropArea(0, 0, 240, 320), PdfPageCrop().bounds(240, 320))
        assertFalse(PdfPageCrop(0, 1).isOriginal)
        assertFalse(PdfPageCrop(1, 0).isOriginal)
        assertEquals(PdfCropArea(24, 0, 192, 320), PdfPageCrop(10, 0).bounds(240, 320))
        assertEquals(PdfCropArea(0, 64, 240, 192), PdfPageCrop(0, 20).bounds(240, 320))
        assertEquals(PdfCropArea(20, 20, 160, 60), PdfPageCrop(10, 20).bounds(200, 100))
    }

    @Test fun `extreme source dimensions use safe arithmetic and retain bounded positive areas`() {
        val sizes = listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, Int.MAX_VALUE to Int.MAX_VALUE,
            612 to 792, 792 to 612, 2048 to 2048)
        for ((width, height) in sizes) for (horizontal in 0..20) for (vertical in 0..20) {
            val area = PdfPageCrop(horizontal, vertical).bounds(width, height)
            assertTrue(area.left >= 0 && area.top >= 0 && area.width > 0 && area.height > 0)
            assertEquals(width.toLong(), 2L * area.left + area.width)
            assertEquals(height.toLong(), 2L * area.top + area.height)
            assertTrue(area.left.toLong() + area.width <= width)
            assertTrue(area.top.toLong() + area.height <= height)
        }
        assertEquals(PdfCropArea(429_496_729, 429_496_729, 1_288_490_189, 1_288_490_189),
            PdfPageCrop(20, 20).bounds(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun `rounding leaves at least one pixel and never removes asymmetric opposite margins`() {
        for (width in 1..4) for (height in 1..4) {
            assertEquals(PdfCropArea(0, 0, width, height), PdfPageCrop(20, 20).bounds(width, height))
        }
        assertEquals(PdfCropArea(1, 1, 3, 3), PdfPageCrop(20, 20).bounds(5, 5))
        assertEquals(PdfCropArea(1, 3, 7, 9), PdfPageCrop(20, 20).bounds(9, 15))
    }

    @Test fun `percentages outside the supported interval cannot enter a crop value`() {
        for (invalid in listOf(-1, 21, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertTrue(runCatching { PdfPageCrop(invalid, 0) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { PdfPageCrop(0, invalid) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun `nonpositive native dimensions and invalid area shapes are rejected`() {
        for ((width, height) in listOf(0 to 320, 240 to 0, -1 to 1, 1 to Int.MIN_VALUE)) {
            assertTrue(runCatching { PdfPageCrop(20, 20).bounds(width, height) }.exceptionOrNull() is IllegalArgumentException)
        }
        for (area in listOf({ PdfCropArea(-1, 0, 1, 1) }, { PdfCropArea(0, -1, 1, 1) },
            { PdfCropArea(0, 0, 0, 1) }, { PdfCropArea(0, 0, 1, 0) })) {
            assertTrue(runCatching(area).exceptionOrNull() is IllegalArgumentException)
        }
    }
}
