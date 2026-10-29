package com.yaprak.reader.feature.reader

import com.yaprak.reader.reader.ReadingPaceObservation
import java.util.ArrayDeque
import kotlin.math.ceil

internal sealed interface ReadingTimeEstimate {
    data object Measuring : ReadingTimeEstimate
    /** The engine reaching 1 does not prove that the final visible text was read. */
    data object EndPosition : ReadingTimeEstimate
    /** chapterMinutes=0 means an end position, never a claim of completion or zero reading time. */
    data class Remaining(val minutes: Int, val chapterMinutes: Int? = null) : ReadingTimeEstimate
}

/** Main-confined, transient measured progress/time pairs; no history, default WPM or coverage writes. */
internal class ReadingPaceMeter {
    private data class Step(val progression: Double, val elapsedMillis: Long)
    private val steps = ArrayDeque<Step>()
    private var revision: Long? = null
    private var lastObserved: Long? = null
    private var lastTick: Long? = null
    private var counting = false
    private var anchor: Double? = null
    private var lastPoint: Double? = null
    private var elapsed = 0L

    fun reset() {
        revision = null; lastObserved = null; lastTick = null; clearWindow()
    }

    fun update(observation: ReadingPaceObservation?, permitted: Boolean, now: Long): ReadingTimeEstimate {
        val previousTick = lastTick
        if (now < 0 || (previousTick != null &&
                    (now < previousTick || now - previousTick > MAX_TICK_GAP_MILLIS))) {
            clearWindow(); lastTick = null
        }
        if (now < 0) return ReadingTimeEstimate.Measuring
        val point = observation?.totalProgression
        val valid = observation != null && observation.revision >= 0 &&
            (revision == null || observation.revision >= revision!!) &&
            observation.observedAtMillis in 0..now && now - observation.observedAtMillis <= MAX_TICK_GAP_MILLIS &&
            (lastObserved == null || observation.observedAtMillis >= lastObserved!!) &&
            (point == null || point.isFinite() && point in 0.0..1.0)
        if (!valid) {
            clearWindow(); lastTick = now
            return ReadingTimeEstimate.Measuring
        }
        if (revision != observation!!.revision) {
            clearWindow(); lastTick = null; revision = observation.revision
        }
        lastObserved = observation.observedAtMillis
        // Charge only the previously admitted interval, including the exact moment permission stops.
        lastTick?.let { if (counting) elapsed += now - it }
        lastTick = now
        counting = permitted && point != null
        if (point == null) return ReadingTimeEstimate.Measuring
        val previous = anchor
        val previousPoint = lastPoint
        lastPoint = point
        when {
            previous == null -> anchor = point
            point < previous - POSITION_EPSILON || previousPoint?.let { point < it - POSITION_EPSILON } == true -> rebase(point)
            !permitted && point != previous -> { anchor = point; elapsed = 0 }
            permitted && point - previous > POSITION_EPSILON -> {
                val delta = point - previous
                if (delta > MAX_STEP_PROGRESSION || elapsed > MAX_STEP_MILLIS) {
                    rebase(point)
                } else if (elapsed >= MIN_STEP_MILLIS) {
                    steps.addLast(Step(delta, elapsed))
                    while (steps.size > MAX_STEPS || steps.sumOf { it.elapsedMillis } > MAX_WINDOW_MILLIS)
                        steps.removeFirst()
                    anchor = point; elapsed = 0
                }
            }
        }
        if (elapsed > MAX_STEP_MILLIS) rebase(point)
        if (point >= 1.0 - POSITION_EPSILON) return ReadingTimeEstimate.EndPosition
        val duration = steps.sumOf { it.elapsedMillis }
        val progress = steps.sumOf { it.progression }
        if (steps.size < MIN_STEPS || duration < MIN_SAMPLE_MILLIS || progress < MIN_SAMPLE_PROGRESSION) {
            return ReadingTimeEstimate.Measuring
        }
        val minutes = ceil((1.0 - point) * duration / progress / 60_000.0)
        return if (minutes.isFinite() && minutes in 1.0..MAX_ESTIMATE_MINUTES.toDouble())
            ReadingTimeEstimate.Remaining(minutes.toInt(), chapterMinutes(observation, point, duration, progress))
            else ReadingTimeEstimate.Measuring
    }

    private fun chapterMinutes(observation: ReadingPaceObservation, point: Double, duration: Long, progress: Double): Int? {
        val id = observation.chapterEntryId ?: return null
        val href = observation.resourceHref ?: return null
        val start = observation.rangeStart ?: return null
        val end = observation.rangeEnd ?: return null
        val local = observation.resourceProgression ?: return null
        if (id.isBlank() || id.length > 128 || id.any(Char::isISOControl) || href.isBlank() || href.length > 4096 ||
            href.any(Char::isISOControl) || !start.isFinite() || !end.isFinite() || start !in 0.0..<1.0 ||
            end !in 0.0..1.0 || end <= start || !local.isFinite() || local !in 0.0..1.0 ||
            point < start - POSITION_EPSILON || point > end + POSITION_EPSILON ||
            kotlin.math.abs(start + local * (end - start) - point) > POSITION_EPSILON) return null
        val minutes = ceil((end - point).coerceAtLeast(0.0) * duration / progress / 60_000.0)
        return minutes.takeIf { it.isFinite() && it in 0.0..MAX_ESTIMATE_MINUTES.toDouble() }?.toInt()
    }

    private fun rebase(point: Double) { anchor = point; lastPoint = point; elapsed = 0; steps.clear() }
    private fun clearWindow() { counting = false; anchor = null; lastPoint = null; elapsed = 0; steps.clear() }

    companion object {
        const val MAX_TICK_GAP_MILLIS = 5_000L
        const val MIN_STEP_MILLIS = 10_000L
        const val MAX_STEP_MILLIS = 600_000L
        const val MIN_SAMPLE_MILLIS = 90_000L
        const val MIN_STEPS = 3
        const val MAX_STEPS = 12
        const val MAX_WINDOW_MILLIS = 1_800_000L
        const val MAX_STEP_PROGRESSION = 0.05
        const val MIN_SAMPLE_PROGRESSION = 0.00001
        const val MAX_ESTIMATE_MINUTES = 10_080
        private const val POSITION_EPSILON = 0.000001
    }
}
