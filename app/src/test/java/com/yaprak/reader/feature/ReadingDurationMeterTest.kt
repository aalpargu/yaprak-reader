package com.yaprak.reader.feature

import com.yaprak.reader.domain.statistics.*
import com.yaprak.reader.feature.reader.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ReadingDurationMeterTest {
    private val book = DurationBook("book", "a".repeat(64))
    private val start = Instant.parse("2026-10-05T09:00:00Z").toEpochMilli()
    private fun moment(ms: Long, wall: Long = start + ms, zone: String = "Europe/Istanbul") = DurationMoment(ms, wall, zone)
    private fun begin(meter: ReadingDurationMeter, at: DurationMoment = moment(0)) {
        meter.claim("one", book, at); meter.update("one", book, true, at)
    }

    @Test fun `only allowed intervals measured and cumulative checkpoint identities stay stable`() {
        val meter = ReadingDurationMeter(); begin(meter)
        for (second in 1L..9L) assertTrue(meter.tick("one", moment(second * 1000)).isEmpty())
        val first = meter.tick("one", moment(10_000)).single()
        assertEquals(10_000L, first.elapsedMillis)
        val final = meter.update("one", book, false, moment(12_000)).single()
        assertEquals(first.id, final.id); assertEquals(12_000L, final.elapsedMillis)
        assertTrue(meter.tick("one", moment(16_000)).isEmpty())
        validateReadingDurationReceipt(final)
    }

    @Test fun `owner handoff closes old interval and stale pause or tick cannot affect new owner`() {
        val meter = ReadingDurationMeter(); begin(meter)
        val old = meter.claim("two", book, moment(1500)).single()
        assertEquals(1500L, old.elapsedMillis)
        meter.update("two", book, true, moment(1500))
        assertTrue(meter.release("one", moment(2000)).isEmpty())
        assertTrue(meter.update("one", book, false, moment(2000)).isEmpty())
        assertTrue(meter.tick("one", moment(2000)).isEmpty())
        val next = meter.release("two", moment(3000)).single()
        assertEquals(1500L, next.elapsedMillis); assertNotEquals(old.id, next.id)
    }

    @Test fun `book content change closes old measured proof and creates distinct identity`() {
        val meter = ReadingDurationMeter(); begin(meter)
        val changed = book.copy(hash = "b".repeat(64))
        val old = meter.update("one", changed, true, moment(1000)).single()
        val next = meter.release("one", moment(2000)).single()
        assertEquals(book.hash, old.contentSha256); assertEquals(changed.hash, next.contentSha256)
        assertEquals(1000L, old.elapsedMillis); assertEquals(1000L, next.elapsedMillis)
    }

    @Test fun `long ticker gap excludes uncertain elapsed and starts fresh`() {
        val meter = ReadingDurationMeter(); begin(meter)
        meter.tick("one", moment(1000))
        val before = meter.tick("one", moment(10_000)).single()
        val after = meter.release("one", moment(11_000)).single()
        assertEquals(1000L, before.elapsedMillis); assertEquals(1000L, after.elapsedMillis)
        assertNotEquals(before.id, after.id)
    }

    @Test fun `wall forward rewind and zone jumps exclude crossing intervals`() {
        for (kind in listOf("forward", "rewind", "zone", "drift")) {
            val meter = ReadingDurationMeter(); begin(meter); meter.tick("one", moment(1000))
            val jump = when (kind) {
                "forward" -> moment(2000, start + 3_600_000)
                "rewind" -> moment(2000, start - 1000)
                "zone" -> moment(2000, zone = "UTC")
                else -> moment(2000, start + 2101)
            }
            val before = meter.tick("one", jump).single()
            val after = meter.release("one", jump.copy(monotonicMillis = 3000, wallMillis = jump.wallMillis + 1000)).single()
            assertEquals(1000L, before.elapsedMillis); assertEquals(1000L, after.elapsedMillis)
            validateReadingDurationReceipt(before); validateReadingDurationReceipt(after)
        }
    }

    @Test fun `normal midnight splits exact measured delta with separate immutable identities`() {
        val wall = Instant.parse("2026-10-05T20:59:59Z").toEpochMilli()
        val meter = ReadingDurationMeter(); begin(meter, moment(0, wall))
        val previous = meter.tick("one", moment(2000, wall + 2000)).single()
        val next = meter.release("one", moment(3000, wall + 3000)).single()
        assertEquals("2026-10-05", previous.localDate); assertEquals(1000L, previous.elapsedMillis)
        assertEquals("2026-10-06", next.localDate); assertEquals(2000L, next.elapsedMillis)
        assertEquals(previous.endedAt, next.startedAt)
        validateReadingDurationReceipt(previous); validateReadingDurationReceipt(next)
    }

    @Test fun `DST midnight uses actual Europe Berlin civil boundary`() {
        val wall = Instant.parse("2026-03-29T21:59:59Z").toEpochMilli()
        val meter = ReadingDurationMeter(); begin(meter, moment(0, wall, "Europe/Berlin"))
        val previous = meter.tick("one", moment(2000, wall + 2000, "Europe/Berlin")).single()
        val next = meter.release("one", moment(3000, wall + 3000, "Europe/Berlin")).single()
        assertEquals("2026-03-29", previous.localDate); assertEquals("2026-03-30", next.localDate)
        assertEquals(3000L, previous.elapsedMillis + next.elapsedMillis)
        validateReadingDurationReceipt(previous); validateReadingDurationReceipt(next)
    }

    @Test fun `zero and invalid moments create no durable receipts`() {
        val meter = ReadingDurationMeter(); begin(meter)
        assertTrue(meter.release("one", moment(0)).isEmpty())
        begin(meter, moment(0, -1)); assertTrue(meter.release("one", moment(1000, -1)).isEmpty())
        begin(meter, moment(0, zone = "invalid/zone"))
        assertTrue(meter.release("one", moment(1000, zone = "invalid/zone")).isEmpty())
    }

    @Test fun `checkpoint does not close interval but background does`() {
        val meter = ReadingDurationMeter(); begin(meter)
        val checkpoint = meter.checkpoint(moment(1000)).single()
        val final = meter.release("one", moment(2000)).single()
        assertEquals(checkpoint.id, final.id); assertEquals(2000L, final.elapsedMillis)
        assertTrue(meter.checkpoint(moment(3000)).isEmpty())
    }
}
