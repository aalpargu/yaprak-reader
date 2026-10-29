package com.yaprak.reader.formats.epub

import org.junit.Assert.*
import org.junit.Test

class EpubPaceMapTest {
    @Test fun `uses the actual unequal catalogue extents without assuming equal chapters`() {
        val map = checkNotNull(EpubPaceMap.create(listOf("a", "b", "c"), listOf(0.0, .1, .7)))
        assertEquals(.05, map.range("a")!!.position(.5)!!, .000000000001)
        assertEquals(.4, map.range("b")!!.position(.5)!!, .000000000001)
        assertEquals(.85, map.range("c")!!.position(.5)!!, .000000000001)
    }
    @Test fun `a real one-anchor per resource catalogue still permits continuous within-resource progress`() {
        val map = checkNotNull(EpubPaceMap.create(listOf("a", "b", "c"), listOf(0.0, 1.0 / 3, 2.0 / 3)))
        assertEquals(.01, map.range("a")!!.position(.03)!!, .000000000001)
    }
    @Test fun `unknown resource never gets a synthetic range`() {
        assertNull(EpubPaceMap.create(listOf("a"), listOf(0.0))!!.range("other"))
    }
    @Test fun `missing nonfinite or out of range catalogue values are rejected`() {
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.0))
            assertNull(EpubPaceMap.create(listOf("a", "b"), listOf(0.0, value)))
        assertNull(EpubPaceMap.create(listOf("a"), listOf(.1)))
    }
    @Test fun `duplicate or decreasing starts cannot establish resource extents`() {
        assertNull(EpubPaceMap.create(listOf("a", "b"), listOf(0.0, 0.0)))
        assertNull(EpubPaceMap.create(listOf("a", "b", "c"), listOf(0.0, .7, .1)))
    }
    @Test fun `empty mismatched duplicate and oversized resources are rejected`() {
        assertNull(EpubPaceMap.create(emptyList(), emptyList()))
        assertNull(EpubPaceMap.create(listOf("a", "b"), listOf(0.0)))
        assertNull(EpubPaceMap.create(listOf("a", "a"), listOf(0.0, .5)))
        assertNull(EpubPaceMap.create(listOf(""), listOf(0.0)))
        assertNull(EpubPaceMap.create(List(10_001) { "$it" }, List(10_001) { it / 10_001.0 }))
    }
    @Test fun `resource progression and malformed direct ranges fail closed`() {
        for (p in listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, -.1, 1.1))
            assertNull(EpubPaceRange(0.0, .5).position(p))
        for (range in listOf(EpubPaceRange(.5, .1), EpubPaceRange(Double.NaN, 1.0), EpubPaceRange(0.0, 1.1)))
            assertNull(range.position(.5))
    }
    @Test fun `resource endpoints map to the declared extent without claiming the text is read`() {
        val range = EpubPaceRange(.2, .6)
        assertEquals(.2, range.position(0.0)!!, 0.0)
        assertEquals(.6, range.position(1.0)!!, .000000000001)
    }
    @Test fun `mutating input lists cannot alter the admitted map`() {
        val names = mutableListOf("a", "b"); val starts = mutableListOf<Double?>(0.0, .5)
        val map = checkNotNull(EpubPaceMap.create(names, starts))
        names[0] = "x"; starts[1] = .9
        assertEquals(.25, map.range("a")!!.position(.5)!!, 0.0)
        assertNull(map.range("x"))
    }
}
