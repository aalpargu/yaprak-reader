package com.yaprak.reader.feature

import com.yaprak.reader.domain.statistics.*
import com.yaprak.reader.feature.statistics.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReadingStreakTest {
    private val today = LocalDate.parse("2026-10-09")
    private fun days(vararg dates: String) = dates.map { ReadingDurationDay(it, 1) }
    @Test fun emptyAndZeroDurationDaysCannotInventReading() {
        assertEquals(ReadingStreak(0, 0, null), readingStreak(emptyList(), today))
        assertEquals(ReadingStreak(0, 0, null), readingStreak(listOf(ReadingDurationDay(today.toString(), 0)), today))
    }
    @Test fun unorderedPositiveDaysCountOnceWithoutAMinuteOrGoalThreshold() {
        assertEquals(ReadingStreak(3, 3, today), readingStreak(days("2026-10-08", "2026-10-09", "2026-10-07"), today))
    }
    @Test fun yesterdaySeriesRemainsCurrentUntilTodayHasFinished() {
        val source = days("2026-10-07", "2026-10-08")
        assertEquals(ReadingStreak(2, 2, today.minusDays(1)), readingStreak(source, today))
        assertEquals(ReadingStreak(0, 2, today.minusDays(1)), readingStreak(source, today.plusDays(1)))
    }
    @Test fun aMissingOrZeroDayBreaksTheCurrentRunButRetainsTheRecordedLongest() {
        val source = days("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-07", "2026-10-09") + ReadingDurationDay("2026-10-08", 0)
        assertEquals(ReadingStreak(1, 3, today), readingStreak(source, today))
    }
    @Test fun futureRowsCannotExtendCurrentLongestOrLastActualDay() {
        val source = days("2026-10-08", "2026-10-09", "2026-10-10", "2026-10-11", "2026-10-12")
        assertEquals(ReadingStreak(2, 2, today), readingStreak(source, today))
        assertEquals(ReadingStreak(0, 0, null), readingStreak(days("2026-10-10"), today))
    }
    @Test fun leapMonthAndYearBoundariesAreCivilDaysRatherThanWallClockHours() {
        assertEquals(3, readingStreak(days("2024-02-28", "2024-02-29", "2024-03-01"), LocalDate.parse("2024-03-01")).currentDays)
        assertEquals(3, readingStreak(days("2025-12-31", "2026-01-01", "2026-01-02"), LocalDate.parse("2026-01-02")).currentDays)
    }
    @Test fun hugeDateGapsNeverCreateOrScanAnAssumedPrefix() {
        assertEquals(ReadingStreak(1, 1, LocalDate.parse("9999-12-31")),
            readingStreak(days("0000-01-01", "1970-01-01", "9999-12-31"), LocalDate.parse("9999-12-31")))
        val many = (0L until 10_000L).map { ReadingDurationDay(today.minusDays(it).toString(), 1) }.reversed()
        assertEquals(ReadingStreak(10_000, 10_000, today), readingStreak(many, today))
    }
    @Test fun duplicateNoncanonicalInvalidOrOutOfRangeDaysAreRejected() {
        for (bad in listOf(days("2026-10-09", "2026-10-09"), days("2026-2-01"), days("2026-02-30"),
            listOf(ReadingDurationDay(today.toString(), -1)), listOf(ReadingDurationDay(today.toString(), MAX_READING_DURATION_TOTAL_MILLIS + 1))))
            assertTrue(runCatching { readingStreak(bad, today) }.isFailure)
    }
}
