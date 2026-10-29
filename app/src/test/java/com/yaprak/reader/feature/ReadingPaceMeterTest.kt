package com.yaprak.reader.feature

import com.yaprak.reader.feature.reader.ReadingPaceMeter
import com.yaprak.reader.feature.reader.ReadingTimeEstimate
import com.yaprak.reader.reader.ReadingPaceObservation
import org.junit.Assert.*
import org.junit.Test

class ReadingPaceMeterTest {
    private class Harness {
        val meter = ReadingPaceMeter()
        var now = 0L
        var revision = 0L
        var point: Double? = .1
        var permitted = true
        var result: ReadingTimeEstimate = ReadingTimeEstimate.Measuring
        fun update() = meter.update(ReadingPaceObservation(revision, point, now), permitted, now).also { result = it }
        fun wait(seconds: Int) { repeat(seconds) { now += 1_000; update() } }
        fun step(afterSeconds: Int = 30, delta: Double = .01) {
            wait(afterSeconds); point = point!! + delta; update()
        }
        fun calibrate() { update(); repeat(3) { step() }; assertTrue(result is ReadingTimeEstimate.Remaining) }
    }

    @Test fun `no history or default speed supplies an estimate before paired measurements`() {
        val h = Harness(); assertEquals(ReadingTimeEstimate.Measuring, h.update())
        h.wait(90); assertEquals(ReadingTimeEstimate.Measuring, h.result)
        h.step(); h.step(); assertEquals(ReadingTimeEstimate.Measuring, h.result)
    }

    @Test fun `three measured progress intervals estimate whole book remaining time`() {
        val h = Harness(); h.calibrate()
        assertEquals(ReadingTimeEstimate.Remaining(44), h.result)
    }

    @Test fun `three fast steps still require ninety seconds of measured time`() {
        val h = Harness(); h.update(); repeat(3) { h.step(10) }
        assertEquals(ReadingTimeEstimate.Measuring, h.result)
        h.step(60); assertTrue(h.result is ReadingTimeEstimate.Remaining)
    }

    @Test fun `book position without enough real progress remains uncalibrated`() {
        val h = Harness(); h.update(); repeat(3) { h.step(delta = .000002) }
        assertEquals(ReadingTimeEstimate.Measuring, h.result)
    }

    @Test fun `a five thousand page book can calibrate from three measured pages`() {
        val h = Harness(); h.update(); repeat(3) { h.step(delta = 1.0 / 5_000) }
        assertEquals(ReadingTimeEstimate.Remaining(2249), h.result)
    }

    @Test fun `three slow ten minute pages fit the bounded recent window`() {
        val h = Harness(); h.update(); repeat(3) { h.step(600) }
        assertEquals(ReadingTimeEstimate.Remaining(870), h.result)
    }

    @Test fun `book endpoint never returns zero or completion even after calibration`() {
        val h = Harness(); h.point = .96; h.calibrate(); h.step(delta = .01)
        assertEquals(ReadingTimeEstimate.EndPosition, h.result)
    }

    @Test fun `SDK jump revision invalidates even a small forward jump and old rate`() {
        val h = Harness(); h.calibrate(); h.wait(30); h.revision++; h.point = .14
        assertEquals(ReadingTimeEstimate.Measuring, h.update())
        h.step(); h.step(); assertEquals(ReadingTimeEstimate.Measuring, h.result)
    }

    @Test fun `backtracking resets rate rather than subtracting or reusing the forward window`() {
        val h = Harness(); h.calibrate(); h.point = .12
        assertEquals(ReadingTimeEstimate.Measuring, h.update())
    }

    @Test fun `large unsignalled jump is rejected rather than counted as read distance`() {
        val h = Harness(); h.calibrate(); h.wait(60); h.point = .8
        assertEquals(ReadingTimeEstimate.Measuring, h.update())
    }

    @Test fun `rapid page flick does not add an instantaneous speed sample`() {
        val h = Harness(); h.calibrate(); h.step(1)
        assertEquals(ReadingTimeEstimate.Remaining(43), h.result)
    }

    @Test fun `a rapid sequence beyond the distance bound resets rather than inflating reading speed`() {
        val h = Harness(); h.calibrate(); repeat(6) { h.step(1) }
        assertEquals(ReadingTimeEstimate.Measuring, h.result)
    }

    @Test fun `frequent continuous scroll positions accumulate into measured intervals`() {
        val h = Harness(); h.update()
        repeat(90) { h.now += 1_000; h.point = h.point!! + .0001; h.update() }
        assertEquals(ReadingTimeEstimate.Remaining(149), h.result)
    }

    @Test fun `backtracking within an uncommitted scroll interval still invalidates the rate`() {
        val h = Harness(); h.calibrate(); h.step(1); h.point = .135
        assertEquals(ReadingTimeEstimate.Measuring, h.update())
    }

    @Test fun `modal time does not change measured speed`() {
        val h = Harness(); h.calibrate(); h.permitted = false; h.update(); h.wait(300)
        assertEquals(ReadingTimeEstimate.Remaining(44), h.result)
        h.permitted = true; h.update(); h.step()
        assertEquals(ReadingTimeEstimate.Remaining(43), h.result)
    }

    @Test fun `progress while a modal is open is not accepted as another measured step`() {
        val h = Harness(); h.update(); h.step(); h.step(); h.permitted = false; h.update()
        h.wait(90); h.point = .13; h.update(); h.permitted = true; h.update()
        assertEquals(ReadingTimeEstimate.Measuring, h.result)
        h.step(); assertTrue(h.result is ReadingTimeEstimate.Remaining)
    }

    @Test fun `temporary visibility probe gap excludes loading time and preserves prior measured dwell`() {
        val h = Harness(); h.update(); h.wait(30); h.point = null; h.update(); h.wait(20)
        h.point = .11; h.update(); repeat(2) { h.step() }
        assertEquals(ReadingTimeEstimate.Remaining(44), h.result)
    }

    @Test fun `large scheduler gap cannot turn unknown wall time into reading`() {
        val h = Harness(); h.calibrate(); h.now += 60_000
        assertEquals(ReadingTimeEstimate.Measuring, h.update())
    }

    @Test fun `monotonic clock rollback cannot create a negative interval`() {
        val h = Harness(); h.calibrate(); h.now -= 1_000
        assertEquals(ReadingTimeEstimate.Measuring, h.update())
    }

    @Test fun `stale future missing or nonfinite observations fail closed`() {
        val h = Harness(); h.calibrate()
        val observations = listOf(null, ReadingPaceObservation(0, .14, h.now - 5_001),
            ReadingPaceObservation(0, .14, h.now + 1), ReadingPaceObservation(0, Double.NaN, h.now),
            ReadingPaceObservation(0, Double.POSITIVE_INFINITY, h.now), ReadingPaceObservation(0, -.1, h.now),
            ReadingPaceObservation(0, 1.01, h.now), ReadingPaceObservation(-1, .14, h.now))
        observations.forEach { assertEquals(ReadingTimeEstimate.Measuring, h.meter.update(it, true, h.now)) }
    }

    @Test fun `late previous revision cannot replace current proof even after another invalid packet`() {
        val h = Harness(); h.calibrate(); h.revision = 4; h.update()
        h.meter.update(ReadingPaceObservation(4, Double.NaN, h.now), true, h.now)
        assertEquals(ReadingTimeEstimate.Measuring,
            h.meter.update(ReadingPaceObservation(3, .5, h.now), true, h.now))
        h.step(); h.step(); assertEquals(ReadingTimeEstimate.Measuring, h.result)
    }

    @Test fun `out of order timestamps cannot be integrated into the pace window`() {
        val h = Harness(); h.calibrate()
        assertEquals(ReadingTimeEstimate.Measuring,
            h.meter.update(ReadingPaceObservation(0, .14, h.now - 1), true, h.now))
        h.step(); h.step(); assertEquals(ReadingTimeEstimate.Measuring, h.result)
    }

    @Test fun `a long stationary page clears the old rate rather than pretending a countdown`() {
        val h = Harness(); h.calibrate(); h.wait(601)
        assertEquals(ReadingTimeEstimate.Measuring, h.result)
    }

    @Test fun `explicit owner reset discards rate before a new book or resumed Activity`() {
        val h = Harness(); h.calibrate(); h.meter.reset()
        assertEquals(ReadingTimeEstimate.Measuring, h.update())
    }

    @Test fun `latest bounded window replaces earlier pace instead of retaining lifetime averages`() {
        val h = Harness(); h.point = .05; h.calibrate()
        repeat(12) { h.step(60, .01) }
        assertEquals(ReadingTimeEstimate.Remaining(80), h.result)
    }

    @Test fun `small rounding noise is not a backwards reading action`() {
        val h = Harness(); h.calibrate(); h.point = h.point!! - .0000001
        assertTrue(h.update() is ReadingTimeEstimate.Remaining)
    }
}
