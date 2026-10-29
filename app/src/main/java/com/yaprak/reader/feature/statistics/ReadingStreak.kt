package com.yaprak.reader.feature.statistics

import com.yaprak.reader.domain.statistics.MAX_READING_DURATION_TOTAL_MILLIS
import com.yaprak.reader.domain.statistics.ReadingDurationDay
import java.time.LocalDate

internal data class ReadingStreak(val currentDays: Int, val longestDays: Int, val lastReadingDate: LocalDate?)

/** Saved civil-day labels only. A gap is tested in a set, never scanned across missing years. */
internal fun readingStreak(days: List<ReadingDurationDay>, today: LocalDate): ReadingStreak {
    val seen = HashSet<Long>(days.size)
    val positive = HashSet<Long>(days.size)
    val todayEpoch = today.toEpochDay()
    var last: Long? = null
    for (day in days) {
        val date = LocalDate.parse(day.localDate)
        require(day.localDate.length == 10 && date.toString() == day.localDate)
        require(day.elapsedMillis in 0..MAX_READING_DURATION_TOTAL_MILLIS)
        val epoch = date.toEpochDay()
        require(seen.add(epoch))
        if (day.elapsedMillis > 0 && epoch <= todayEpoch) {
            positive.add(epoch)
            last = last?.let { maxOf(it, epoch) } ?: epoch
        }
    }
    var longest = 0
    var current = 0
    for (start in positive) {
        if (start - 1 in positive) continue
        var end = start
        var length = 1
        while (end + 1 in positive) { end++; length++ }
        longest = maxOf(longest, length)
        if (end == last && todayEpoch - end in 0L..1L) current = length
    }
    return ReadingStreak(current, longest, last?.let(LocalDate::ofEpochDay))
}
