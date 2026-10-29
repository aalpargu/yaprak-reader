package com.yaprak.reader.formats.epub

import org.junit.Assert.*
import org.junit.Test

class EpubReadingPaceTest {
    private var now = 0L
    private fun source() = EpubReadingPace { now }
    private fun EpubReadingPace.show(href: String = "one.xhtml", local: Double? = .123456789,
        width: Int = 320, height: Int = 600, range: EpubPaceRange? = EpubPaceRange(0.0, 1.0)) =
        visible(href, local, range, width, height)

    @Test fun `keeps exact live progression rather than rounding to integer percent`() {
        val s = source(); assertNull(s.observations.value.totalProgression)
        s.show(); assertEquals(.123456789, s.observations.value.totalProgression!!, 0.0)
        assertEquals(0L, s.observations.value.revision)
    }

    @Test fun `preloaded resource or accepted command cannot settle a pending jump`() {
        val s = source(); s.show(); s.invalidate(waitForPage = true)
        val revision = s.observations.value.revision
        now += 1_000; s.show(local = .7); assertNull(s.observations.value.totalProgression)
        assertEquals(revision, s.observations.value.revision)
        s.pageChanged(); assertNull(s.observations.value.totalProgression)
        assertTrue(s.observations.value.revision > revision)
        s.show(local = .7); assertEquals(.7, s.observations.value.totalProgression!!, 0.0)
    }

    @Test fun `ordinary pagination keeps revision so real reading intervals can accumulate`() {
        val s = source(); s.show(); val revision = s.observations.value.revision
        s.clearPosition(); now += 1_000; s.pageChanged(); s.show(local = .13)
        assertEquals(revision, s.observations.value.revision)
        assertEquals(.13, s.observations.value.totalProgression!!, 0.0)
    }

    @Test fun `resource and viewport changes start fresh baselines`() {
        val s = source(); s.show(); val start = s.observations.value.revision
        s.show(href = "two.xhtml"); assertEquals(start + 1, s.observations.value.revision)
        s.show(href = "two.xhtml", width = 600, height = 320)
        assertEquals(start + 2, s.observations.value.revision)
        s.show(href = "two.xhtml", width = 600, height = 320)
        assertEquals(start + 2, s.observations.value.revision)
    }

    @Test fun `layout submission needs actual pagination before any pace position`() {
        val s = source(); s.show(); s.invalidate(waitForPage = true)
        s.show(local = .2); assertNull(s.observations.value.totalProgression)
        s.pageChanged(); s.show(local = .2); assertEquals(.2, s.observations.value.totalProgression!!, 0.0)
    }

    @Test fun `missing invalid progress or zero size never guesses a position`() {
        val s = source()
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.1).forEach {
            s.show(local = it); assertNull(s.observations.value.totalProgression)
        }
        s.show(width = 0); assertNull(s.observations.value.totalProgression)
        s.show(height = 0); assertNull(s.observations.value.totalProgression)
        s.show(href = ""); assertNull(s.observations.value.totalProgression)
    }

    @Test fun `live within resource progress interpolates the actual SDK extent instead of a coarse total anchor`() {
        val s = source(); s.show(local = .03, range = EpubPaceRange(0.0, 1.0 / 3))
        val sample = s.observations.value
        assertEquals(.01, sample.totalProgression!!, .000000000001)
        assertEquals("one.xhtml", sample.resourceHref)
        assertEquals(.03, sample.resourceProgression!!, 0.0)
        assertEquals(0.0, sample.rangeStart!!, 0.0)
        assertEquals(1.0 / 3, sample.rangeEnd!!, 0.0)
    }

    @Test fun `missing catalogue extent cannot fall back to an invented or stale book position`() {
        val s = source(); s.show(); s.show(range = null)
        assertNull(s.observations.value.totalProgression)
        assertNull(s.observations.value.resourceProgression)
    }

    @Test fun `disposal is final and late page or visibility callbacks cannot revive a position`() {
        val s = source(); s.show(); s.dispose(); val closed = s.observations.value
        assertNull(closed.totalProgression)
        now += 1_000; s.pageChanged(); s.show(); s.invalidate(); s.clearPosition()
        assertEquals(closed, s.observations.value)
    }
}
