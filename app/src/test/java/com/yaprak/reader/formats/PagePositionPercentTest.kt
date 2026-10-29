package com.yaprak.reader.formats

import com.yaprak.reader.reader.pagePositionPercent
import org.junit.Assert.*
import org.junit.Test

class PagePositionPercentTest {
    @Test fun invalidOrUnknownPageAndInvalidCountHaveNoPosition() {
        assertNull(pagePositionPercent(null, 5)); assertNull(pagePositionPercent(-1, 5))
        assertNull(pagePositionPercent(5, 5)); assertNull(pagePositionPercent(0, 0))
        assertNull(pagePositionPercent(0, -1)); assertNull(pagePositionPercent(Int.MAX_VALUE, Int.MAX_VALUE))
    }
    @Test fun originalSelectedPageDeterminesPositionWithoutClaimingVisitedPrefix() {
        assertEquals(listOf(20, 40, 60, 80, 100), (0 until 5).map { pagePositionPercent(it, 5) })
        assertEquals(100, pagePositionPercent(0, 1)); assertEquals(33, pagePositionPercent(0, 3))
        assertEquals(66, pagePositionPercent(1, 3)); assertEquals(100, pagePositionPercent(2, 3))
        assertEquals(0, pagePositionPercent(0, 2000)); assertEquals(99, pagePositionPercent(1998, 2000))
    }
    @Test fun largeCountUsesOverflowSafeArithmeticAndOnlyActualLastPageReachesEndpoint() {
        assertEquals(0, pagePositionPercent(0, Int.MAX_VALUE))
        assertEquals(50, pagePositionPercent(Int.MAX_VALUE / 2, Int.MAX_VALUE))
        assertEquals(99, pagePositionPercent(Int.MAX_VALUE - 2, Int.MAX_VALUE))
        assertEquals(100, pagePositionPercent(Int.MAX_VALUE - 1, Int.MAX_VALUE))
    }
}
