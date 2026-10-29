package com.yaprak.reader.feature.reader

import android.os.SystemClock
import com.yaprak.reader.domain.statistics.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

internal data class DurationMoment(val monotonicMillis: Long, val wallMillis: Long, val zoneId: String)
internal fun interface ReadingDurationClock { fun now(): DurationMoment }
internal object SystemReadingDurationClock : ReadingDurationClock {
    override fun now(): DurationMoment {
        val before = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val after = SystemClock.elapsedRealtime()
        return DurationMoment(before + (after - before) / 2, wall, ZoneId.systemDefault().id)
    }
}
internal data class DurationBook(val id: String, val hash: String)

/** Measured intervals only. It never infers a duration from locations, wall time or a restored receipt. */
internal class ReadingDurationMeter {
    private data class Interval(val id: String, val book: DurationBook, val date: String,
        val zone: String, val start: Long, var end: Long, var elapsed: Long = 0) {
        fun receipt() = ReadingDurationReceipt(id, book.id, book.hash, date, zone, start, end, elapsed)
    }
    private var owner: String? = null
    private var book: DurationBook? = null
    private var eligible = false
    private var sample: DurationMoment? = null
    private var interval: Interval? = null
    private var lastCheckpoint = 0L

    fun claim(nextOwner: String, nextBook: DurationBook, now: DurationMoment): List<ReadingDurationReceipt> {
        if (owner == nextOwner && book == nextBook) return emptyList()
        val result = stop(now)
        owner = nextOwner; book = nextBook
        return result
    }
    fun update(expectedOwner: String, currentBook: DurationBook, allowed: Boolean, now: DurationMoment): List<ReadingDurationReceipt> {
        if (owner != expectedOwner) return emptyList()
        val result = ArrayList<ReadingDurationReceipt>()
        if (book != currentBook) { result += stop(now); book = currentBook }
        if (eligible) result += advance(now)
        if (!allowed) {
            result += finish(); eligible = false
        } else if (!eligible) {
            eligible = true; start(now)
        }
        return result
    }
    fun tick(expectedOwner: String, now: DurationMoment): List<ReadingDurationReceipt> {
        if (owner != expectedOwner || !eligible) return emptyList()
        val result = ArrayList(advance(now))
        if (now.monotonicMillis - lastCheckpoint >= CHECKPOINT_MILLIS) {
            current()?.let { result += it }; lastCheckpoint = now.monotonicMillis
        }
        return result
    }
    fun checkpoint(now: DurationMoment): List<ReadingDurationReceipt> {
        val result = ArrayList<ReadingDurationReceipt>()
        if (eligible) result += advance(now)
        current()?.let { result += it }; lastCheckpoint = now.monotonicMillis
        return result
    }
    fun release(expectedOwner: String, now: DurationMoment): List<ReadingDurationReceipt> {
        if (owner != expectedOwner) return emptyList()
        val result = stop(now); owner = null; book = null
        return result
    }
    private fun stop(now: DurationMoment): List<ReadingDurationReceipt> {
        val result = ArrayList<ReadingDurationReceipt>()
        if (eligible) result += advance(now)
        result += finish(); eligible = false
        return result
    }
    private fun start(now: DurationMoment) {
        if (!valid(now)) { sample = null; interval = null; return }
        val identity = book ?: return
        interval = Interval(UUID.randomUUID().toString(), identity, date(now.wallMillis, now.zoneId),
            now.zoneId, now.wallMillis, now.wallMillis)
        sample = now; lastCheckpoint = now.monotonicMillis
    }
    private fun current() = interval?.takeIf { it.elapsed > 0 }?.receipt()
    private fun finish(): List<ReadingDurationReceipt> {
        val result = current()?.let(::listOf) ?: emptyList()
        sample = null; interval = null
        return result
    }
    private fun advance(now: DurationMoment): List<ReadingDurationReceipt> {
        val previous = sample
        val active = interval
        if (previous == null || active == null) { start(now); return emptyList() }
        val monotonicDelta = now.monotonicMillis - previous.monotonicMillis
        val wallDelta = now.wallMillis - previous.wallMillis
        if (!valid(now) || now.zoneId != previous.zoneId || monotonicDelta !in 0..MAX_TICK_GAP_MILLIS ||
            wallDelta < 0 || abs(wallDelta - monotonicDelta) > CLOCK_DRIFT_MILLIS ||
            active.elapsed > MAX_READING_DURATION_MILLIS - monotonicDelta) {
            // Unknown elapsed belongs to neither day. Close at the last accepted sample.
            val result = finish(); start(now); return result
        }
        val midnight = Instant.ofEpochMilli(previous.wallMillis).atZone(ZoneId.of(previous.zoneId))
            .toLocalDate().plusDays(1).atStartOfDay(ZoneId.of(previous.zoneId)).toInstant().toEpochMilli()
        val result = ArrayList<ReadingDurationReceipt>()
        if (now.wallMillis >= midnight && wallDelta > 0) {
            val before = (monotonicDelta * (midnight - previous.wallMillis) / wallDelta).coerceIn(0, monotonicDelta)
            active.elapsed += before; active.end = midnight
            current()?.let { result += it }
            start(DurationMoment(previous.monotonicMillis + before, midnight, now.zoneId))
            interval?.let { it.elapsed += monotonicDelta - before; it.end = now.wallMillis }
        } else {
            active.elapsed += monotonicDelta; active.end = now.wallMillis
        }
        sample = now
        return result
    }
    private fun valid(now: DurationMoment): Boolean = runCatching {
        require(now.monotonicMillis >= 0 && now.wallMillis in 0..MAX_READING_DURATION_TIMESTAMP)
        require(now.zoneId.isNotEmpty() && now.zoneId.length <= 100 && ZoneId.of(now.zoneId).id == now.zoneId)
        require(date(now.wallMillis, now.zoneId).length == 10)
    }.isSuccess
    private fun date(wall: Long, zone: String) = Instant.ofEpochMilli(wall).atZone(ZoneId.of(zone)).toLocalDate().toString()

    companion object {
        const val TICK_MILLIS = 1_000L
        const val CHECKPOINT_MILLIS = 10_000L
        const val MAX_TICK_GAP_MILLIS = 5_000L
        const val CLOCK_DRIFT_MILLIS = 100L
    }
}
