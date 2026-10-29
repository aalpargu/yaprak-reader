package com.yaprak.reader.feature

import com.yaprak.reader.domain.statistics.ReadingDurationDay
import com.yaprak.reader.domain.statistics.ReadingDurationSnapshot
import com.yaprak.reader.feature.statistics.ReadingStatisticsState
import com.yaprak.reader.feature.statistics.readingMonth
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class ReadingMonthTest {
    private fun state(today: String, vararg days: Pair<String, Long>) = ReadingStatisticsState(LocalDate.parse(today), "Europe/Istanbul",
        ReadingDurationSnapshot(days.map { ReadingDurationDay(it.first, it.second) }, emptyList(), days.sumOf { it.second }), loading = false, active = true)

    @Test fun `leap month aligns Monday through Sunday with distinct recorded zero missing and positive duration`() {
        val result = readingMonth(state("2024-02-29", "2024-02-01" to 0, "2024-02-29" to 1001), YearMonth.of(2024, 2))
        assertEquals(5, result.weeks.size)
        assertEquals(listOf(null, null, null), result.weeks.first().take(3))
        assertEquals(LocalDate.of(2024, 2, 1), result.weeks[0][3]!!.date)
        val days = result.weeks.flatten().filterNotNull()
        assertEquals(29, days.size)
        assertEquals(0L, days.first().elapsedMillis)
        assertNull(days[1].elapsedMillis)
        assertEquals(1001L, days.last().elapsedMillis)
        assertEquals(29, days.map { it.date }.distinct().size)
    }

    @Test fun `month bounds come only from stored civil date labels and today including future-labelled real receipts`() {
        val source = state("2026-10-05", "2025-12-31" to 1, "2027-01-01" to 2)
        val current = readingMonth(source, YearMonth.of(2026, 10))
        assertEquals(YearMonth.of(2025, 12), current.earliest)
        assertEquals(YearMonth.of(2027, 1), current.latest)
        assertEquals(current.earliest, readingMonth(source, YearMonth.of(1900, 1)).month)
        val future = readingMonth(source, YearMonth.of(2027, 1))
        assertEquals(2L, future.weeks.flatten().filterNotNull().first().elapsedMillis)
        assertEquals(current.latest, readingMonth(source, YearMonth.of(2099, 1)).month)
        assertEquals(2, source.snapshot!!.days.size) // No inserted missing-day receipts.
    }

    @Test fun `empty source is current month only and six-week or Monday-first months never invent outside dates`() {
        val empty = state("2026-10-05")
        val result = readingMonth(empty, YearMonth.of(2025, 1))
        assertEquals(YearMonth.of(2026, 10), result.month)
        assertTrue(result.weeks.flatten().filterNotNull().all { it.elapsedMillis == null })
        val august = readingMonth(state("2026-08-31"), YearMonth.of(2026, 8))
        assertEquals(6, august.weeks.size)
        assertTrue(august.weeks.all { it.size == 7 })
        assertEquals(31, august.weeks.flatten().filterNotNull().size)
        val february = readingMonth(state("2021-02-28"), YearMonth.of(2021, 2))
        assertEquals(4, february.weeks.size)
        assertTrue(february.weeks.flatten().all { it != null })
    }
}
