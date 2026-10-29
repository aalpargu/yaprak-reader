package com.yaprak.reader.feature

import com.yaprak.reader.feature.reader.pageIndexForInput
import com.yaprak.reader.feature.reader.pageIndexForSliderProgress
import com.yaprak.reader.feature.reader.sliderProgressForPageIndex
import org.junit.Assert.*
import org.junit.Test

class PageJumpInputTest {
    @Test fun `first last and largest supported page map without overflow`() {
        assertEquals(0, pageIndexForInput("1", 500))
        assertEquals(499, pageIndexForInput(" 500 ", 500))
        assertEquals(1, pageIndexForInput("002", 500))
        assertEquals(Int.MAX_VALUE - 1, pageIndexForInput(Int.MAX_VALUE.toString(), Int.MAX_VALUE))
    }
    @Test fun `invalid numbers are rejected without clamping to a real page`() {
        listOf("", " ", "0", "501", "-1", "+1", "1.5", "1,5", "1e2", "1 2", "2147483648", "x".repeat(1000))
            .forEach { assertNull("Unexpected page for $it", pageIndexForInput(it, 500)) }
        assertNull(pageIndexForInput("1", 0))
    }

    @Test fun `slider maps first midpoint and last pages without integer overflow`() {
        assertEquals(0, pageIndexForSliderProgress(0f, 21))
        assertEquals(10, pageIndexForSliderProgress(0.5f, 21))
        assertEquals(20, pageIndexForSliderProgress(1f, 21))
        assertEquals(Int.MAX_VALUE - 1, pageIndexForSliderProgress(1f, Int.MAX_VALUE))
        assertEquals(0, pageIndexForSliderProgress(0.5f, 1))
        assertEquals(0f, sliderProgressForPageIndex(0, 1))
        assertEquals(0.5f, sliderProgressForPageIndex(10, 21))
        assertEquals(1f, sliderProgressForPageIndex(Int.MAX_VALUE - 1, Int.MAX_VALUE))
    }

    @Test fun `slider rejects unusable bounds and progress`() {
        assertNull(pageIndexForSliderProgress(0.5f, 0))
        assertNull(pageIndexForSliderProgress(Float.NaN, 20))
        assertNull(pageIndexForSliderProgress(Float.POSITIVE_INFINITY, 20))
        assertEquals(0, pageIndexForSliderProgress(-1f, 20))
        assertEquals(19, pageIndexForSliderProgress(2f, 20))
    }
}
