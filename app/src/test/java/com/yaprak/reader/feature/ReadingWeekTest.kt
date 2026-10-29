package com.yaprak.reader.feature

import com.yaprak.reader.domain.statistics.*
import com.yaprak.reader.feature.statistics.ReadingStatisticsState
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReadingWeekTest {
    @Test fun sundayMondayYearBoundaryUsesSavedLocalDatesAndDoesNotIncludeFutureReceipts() {
        val days = listOf(ReadingDurationDay("2025-12-28", 2_000), ReadingDurationDay("2025-12-29", 3_000),
            ReadingDurationDay("2026-01-01", 4_000), ReadingDurationDay("2026-01-04", 5_000), ReadingDurationDay("2026-01-05", 6_000))
        val snapshot = ReadingDurationSnapshot(days, emptyList(), 20_000)
        val thursday = ReadingStatisticsState(LocalDate.parse("2026-01-01"), "Europe/Istanbul", snapshot)
        assertEquals(LocalDate.parse("2025-12-29"), thursday.weekStart); assertEquals(7_000L, thursday.currentWeekMillis)
        val sunday = thursday.copy(today = LocalDate.parse("2026-01-04")); assertEquals(12_000L, sunday.currentWeekMillis)
        val monday = thursday.copy(today = LocalDate.parse("2026-01-05"))
        assertEquals(LocalDate.parse("2026-01-05"), monday.weekStart); assertEquals(6_000L, monday.currentWeekMillis)
        assertSame(snapshot, monday.snapshot); assertEquals(20_000L, monday.snapshot!!.totalMillis)
    }
    @Test fun weekSumsSavedMillisWithoutAssumingTwentyFourHourDaysAndMissingSnapshotIsUnavailable() {
        val snapshot = ReadingDurationSnapshot(listOf(ReadingDurationDay("2026-03-29", 82_800_000),
            ReadingDurationDay("2026-03-30", 1_001)), emptyList(), 82_801_001)
        val sunday = ReadingStatisticsState(LocalDate.parse("2026-03-29"), "Europe/Berlin", snapshot)
        assertEquals(82_800_000L, sunday.currentWeekMillis)
        assertEquals(1_001L, sunday.copy(today = LocalDate.parse("2026-03-30")).currentWeekMillis)
        assertNull(sunday.copy(snapshot = null).snapshot)
    }
}
