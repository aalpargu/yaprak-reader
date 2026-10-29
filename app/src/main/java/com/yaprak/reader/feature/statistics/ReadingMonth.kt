package com.yaprak.reader.feature.statistics

import java.time.YearMonth

internal data class ReadingMonth(
    val month: YearMonth,
    val earliest: YearMonth,
    val latest: YearMonth,
    val weeks: List<List<ReadingCalendarDay?>>,
)

/** Civil date labels are retained exactly. A missing receipt stays unknown, not a synthetic zero. */
internal fun readingMonth(state: ReadingStatisticsState, requested: YearMonth): ReadingMonth {
    val snapshot = requireNotNull(state.snapshot)
    val today = YearMonth.from(state.today)
    val first = snapshot.days.minOfOrNull { it.localDate.take(7) }?.let(YearMonth::parse) ?: today
    val last = snapshot.days.maxOfOrNull { it.localDate.take(7) }?.let(YearMonth::parse) ?: today
    val earliest = minOf(first, today)
    val latest = maxOf(last, today)
    val month = requested.coerceIn(earliest, latest)
    val prefix = "$month-"
    val saved = snapshot.days.filter { it.localDate.startsWith(prefix) }.associate { it.localDate to it.elapsedMillis }
    val leading = month.atDay(1).dayOfWeek.value - 1
    val cells = MutableList<ReadingCalendarDay?>(leading) { null }
    (1..month.lengthOfMonth()).forEach { number ->
        val day = month.atDay(number)
        cells += ReadingCalendarDay(day, saved[day.toString()])
    }
    while (cells.size % 7 != 0) cells += null
    return ReadingMonth(month, earliest, latest, cells.chunked(7))
}
