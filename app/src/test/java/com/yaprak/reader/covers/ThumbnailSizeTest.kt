package com.yaprak.reader.covers

import org.junit.Assert.*
import org.junit.Test

class ThumbnailSizeTest {
    @Test fun `both dimensions remain bounded for huge and thin PDF pages`() {
        assertEquals(384 to 384, thumbnailSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(1 to 512, thumbnailSize(1, Int.MAX_VALUE))
        assertEquals(384 to 1, thumbnailSize(Int.MAX_VALUE, 1))
        assertEquals(8 to 12, thumbnailSize(8, 12))
        assertNull(thumbnailSize(0, 12))
        assertNull(thumbnailSize(12, -1))
    }

    @Test fun `raster sampling rejects unsafe source dimensions before allocation`() {
        assertNull(coverSampleSize(Int.MAX_VALUE, 1))
        assertNull(coverSampleSize(16_384, 16_384))
        assertNull(coverSampleSize(0, 1))
        assertEquals(4, coverSampleSize(800, 1200))
        assertEquals(1, coverSampleSize(384, 512))
    }
}
