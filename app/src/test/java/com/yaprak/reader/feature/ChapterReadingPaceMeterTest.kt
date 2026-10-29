package com.yaprak.reader.feature

import com.yaprak.reader.feature.reader.ReadingPaceMeter
import com.yaprak.reader.feature.reader.ReadingTimeEstimate
import com.yaprak.reader.reader.ReadingPaceObservation
import org.junit.Assert.*
import org.junit.Test

class ChapterReadingPaceMeterTest {
    private class Harness {
        val meter = ReadingPaceMeter()
        var now = 0L
        var point = .1
        fun sample() = ReadingPaceObservation(0, point, now)
        fun update(value: ReadingPaceObservation = sample()) = meter.update(value, true, now)
        fun step() {
            repeat(30) { now += 1_000; update() }
            point += .01; update()
        }
        fun calibrate() { update(); repeat(3) { step() } }
        fun chapter(end: Double = .3, id: String? = "0") = sample().copy(resourceHref = "one.xhtml",
            resourceProgression = point / end, rangeStart = 0.0, rangeEnd = end, chapterEntryId = id)
    }

    @Test fun `actual whole TOC boundary estimates chapter using the same measured speed`() {
        val h = Harness(); h.calibrate()
        assertEquals(ReadingTimeEstimate.Remaining(44, 9), h.update(h.chapter()))
    }

    @Test fun `a resource extent alone is never interpreted as a chapter`() {
        val h = Harness(); h.calibrate()
        assertEquals(ReadingTimeEstimate.Remaining(44), h.update(h.chapter(id = null)))
    }

    @Test fun `partial inconsistent or malformed chapter evidence disables only chapter estimate`() {
        val h = Harness(); h.calibrate(); val valid = h.chapter()
        for (bad in listOf(valid.copy(resourceHref = null), valid.copy(resourceHref = "bad\nname"),
            valid.copy(rangeStart = null), valid.copy(rangeStart = .4), valid.copy(rangeEnd = .1),
            valid.copy(rangeEnd = Double.NaN), valid.copy(rangeEnd = 1.1),
            valid.copy(resourceProgression = null), valid.copy(resourceProgression = .9),
            valid.copy(resourceProgression = Double.POSITIVE_INFINITY),
            valid.copy(chapterEntryId = " "), valid.copy(chapterEntryId = "bad\u0000id"))) {
            assertEquals(ReadingTimeEstimate.Remaining(44), h.update(bad))
        }
    }

    @Test fun `chapter boundary is an end position flag rather than a completion claim`() {
        val h = Harness(); h.calibrate()
        val result = h.update(h.chapter(end = h.point)) as ReadingTimeEstimate.Remaining
        assertEquals(44, result.minutes)
        assertEquals(0, result.chapterMinutes)
    }

    @Test fun `TOC evidence does not supply a speed before three measured intervals`() {
        val h = Harness(); h.update(h.chapter())
        repeat(2) { h.step(); assertEquals(ReadingTimeEstimate.Measuring, h.update(h.chapter())) }
        h.step(); assertEquals(ReadingTimeEstimate.Remaining(44, 9), h.update(h.chapter()))
    }

    @Test fun `book endpoint remains an endpoint with chapter evidence and never returns zero book minutes`() {
        val h = Harness(); h.point = .96; h.calibrate(); h.point = 1.0
        assertEquals(ReadingTimeEstimate.EndPosition, h.update(h.chapter(end = 1.0)))
    }
}
