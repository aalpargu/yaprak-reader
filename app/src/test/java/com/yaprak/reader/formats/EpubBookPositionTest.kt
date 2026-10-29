package com.yaprak.reader.formats

import com.yaprak.reader.formats.epub.epubBookPositionPercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubBookPositionTest {
    @Test fun `valid total progression becomes an approximate position percentage`() {
        assertEquals(0, epubBookPositionPercent(0.0, fixedLayout = false))
        assertEquals(50, epubBookPositionPercent(0.5, fixedLayout = false))
        assertEquals(38, epubBookPositionPercent(0.375, fixedLayout = false))
        assertEquals(100, epubBookPositionPercent(1.0, fixedLayout = false))
    }

    @Test fun `missing nonfinite and out of range progression is unavailable`() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            -0.01, 1.01).forEach { progression ->
            assertNull(epubBookPositionPercent(progression, fixedLayout = false))
        }
    }

    @Test fun `fixed layout EPUB never exposes a book position percentage`() {
        listOf(0.0, 0.5, 1.0).forEach { progression ->
            assertNull(epubBookPositionPercent(progression, fixedLayout = true))
        }
    }
}
