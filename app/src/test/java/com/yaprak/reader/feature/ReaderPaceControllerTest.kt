package com.yaprak.reader.feature

import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPaceControllerTest {
    private class Source : ReadingPaceSource {
        override val observations = MutableStateFlow(ReadingPaceObservation(0, .1, 0))
    }
    private class Harness(val scope: TestScope) {
        val source = Source()
        val owner = Any()
        val identity = PaceIdentity("one", "a".repeat(64))
        val controller = ReaderPaceController(scope.backgroundScope) { scope.testScheduler.currentTime }
        var point = .1
        init {
            controller.bind(owner, identity, source)
            controller.permit(owner, identity, true)
            scope.runCurrent()
        }
        fun waitSeconds(seconds: Int) {
            repeat(seconds) {
                scope.advanceTimeBy(1_000)
                source.observations.value = ReadingPaceObservation(0, point, scope.testScheduler.currentTime)
                scope.runCurrent()
            }
        }
        fun step() {
            waitSeconds(30); point += .01
            source.observations.value = ReadingPaceObservation(0, point, scope.testScheduler.currentTime)
            scope.runCurrent()
        }
        fun calibrate() { repeat(3) { step() }; assertTrue(controller.state.value is ReadingTimeEstimate.Remaining) }
    }

    @Test fun `same owner and exact book source rebind does not erase a measured estimate`() = runTest {
        val h = Harness(this); h.calibrate()
        h.controller.bind(h.owner, h.identity, h.source)
        assertEquals(ReadingTimeEstimate.Remaining(44), h.controller.state.value)
    }

    @Test fun `a replacement surface cancels old subscriptions and old permission or pause is inert`() = runTest {
        val h = Harness(this); h.calibrate(); val nextOwner = Any(); val next = Source()
        next.observations.value = ReadingPaceObservation(0, .3, testScheduler.currentTime)
        h.controller.bind(nextOwner, h.identity, next); h.controller.permit(nextOwner, h.identity, true); runCurrent()
        assertEquals(0, h.source.observations.subscriptionCount.value)
        assertEquals(1, next.observations.subscriptionCount.value)
        h.controller.permit(h.owner, h.identity, false); h.controller.suspend(h.owner)
        h.source.observations.value = ReadingPaceObservation(100, .99, testScheduler.currentTime); runCurrent()
        assertEquals(ReadingTimeEstimate.Measuring, h.controller.state.value)
    }

    @Test fun `changed source hash resets rate and old hash cannot grant permission`() = runTest {
        val h = Harness(this); h.calibrate(); val changed = PaceIdentity("one", "b".repeat(64))
        h.controller.bind(h.owner, changed, h.source); runCurrent()
        h.controller.permit(h.owner, h.identity, true)
        repeat(3) { h.step() }
        assertEquals(ReadingTimeEstimate.Measuring, h.controller.state.value)
    }

    @Test fun `suspending foreground clears rate so paused wall time never appears after resume`() = runTest {
        val h = Harness(this); h.calibrate(); h.controller.suspend(h.owner)
        h.waitSeconds(300); h.controller.permit(h.owner, h.identity, true)
        assertEquals(ReadingTimeEstimate.Measuring, h.controller.state.value)
        h.step(); h.step(); assertEquals(ReadingTimeEstimate.Measuring, h.controller.state.value)
        h.step(); assertTrue(h.controller.state.value is ReadingTimeEstimate.Remaining)
    }

    @Test fun `an unsupported replacement removes the UI and every old job`() = runTest {
        val h = Harness(this); h.calibrate(); h.controller.bind(Any(), h.identity, null); runCurrent()
        assertNull(h.controller.state.value); assertEquals(0, h.source.observations.subscriptionCount.value)
        h.waitSeconds(60); assertNull(h.controller.state.value)
    }

    @Test fun `disposing controller releases subscriptions and late observations cannot revive state`() = runTest {
        val h = Harness(this); h.calibrate(); h.controller.detach(); runCurrent()
        assertNull(h.controller.state.value); assertEquals(0, h.source.observations.subscriptionCount.value)
        h.source.observations.value = ReadingPaceObservation(1, .9, testScheduler.currentTime); runCurrent()
        assertNull(h.controller.state.value)
    }
}
