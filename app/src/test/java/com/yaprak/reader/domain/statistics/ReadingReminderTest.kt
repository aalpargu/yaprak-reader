package com.yaprak.reader.domain.statistics

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReadingReminderTest {
    private val istanbul = ZoneId.of("Europe/Istanbul")
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test fun nextReminderIsTodayIfStillAheadOtherwiseTomorrow() {
        val morning = Instant.parse("2026-11-24T06:00:00Z") // 09:00 in Istanbul
        assertEquals(Instant.parse("2026-11-24T17:30:00Z"), nextReadingReminderAt(morning, 20 * 60 + 30, istanbul))
        val evening = Instant.parse("2026-11-24T18:00:00Z") // 21:00
        assertEquals(Instant.parse("2026-11-25T17:30:00Z"), nextReadingReminderAt(evening, 20 * 60 + 30, istanbul))
        // Exactly at the reminder time: the next one is tomorrow (it just fired).
        val exact = Instant.parse("2026-11-24T17:30:00Z")
        assertEquals(Instant.parse("2026-11-25T17:30:00Z"), nextReadingReminderAt(exact, 20 * 60 + 30, istanbul))
    }

    @Test fun daylightSavingChangesKeepTheWallClockTime() {
        // Berlin moves to summer time on 2026-03-29: 20:00 local is 19:00Z before and 18:00Z after.
        val before = Instant.parse("2026-03-28T20:00:00Z")
        assertEquals(Instant.parse("2026-03-29T18:00:00Z"), nextReadingReminderAt(before, 20 * 60, berlin))
        // 02:30 does not exist that night; it is pushed forward rather than skipped a day.
        val night = Instant.parse("2026-03-28T23:00:00Z")
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), nextReadingReminderAt(night, 2 * 60 + 30, berlin))
    }

    @Test fun textNamesTheRemainingMinutesAndStaysQuietOnceTheGoalIsMet() {
        assertEquals("Bugün henüz okumadın · hedefe 20 dk", readingReminderText(0, 20))
        assertEquals("Bugünkü hedefe 8 dk kaldı", readingReminderText(12 * 60_000L + 59_000, 20))
        assertNull(readingReminderText(20 * 60_000L, 20))
        assertNull(readingReminderText(45 * 60_000L, 20))
        assertEquals("Bugün henüz okumadın", readingReminderText(0, null))
        assertNull(readingReminderText(1_000, null))
    }

    @Test fun reminderTimeIsValidatedAndFormatted() {
        validateReadingReminder(null); validateReadingReminder(0); validateReadingReminder(MINUTES_PER_DAY - 1)
        assertTrue(runCatching { validateReadingReminder(MINUTES_PER_DAY) }.isFailure)
        assertTrue(runCatching { validateReadingReminder(-1) }.isFailure)
        assertEquals("07:05", formatReminderTime(7 * 60 + 5))
        assertEquals("23:59", formatReminderTime(MINUTES_PER_DAY - 1))
    }
}
