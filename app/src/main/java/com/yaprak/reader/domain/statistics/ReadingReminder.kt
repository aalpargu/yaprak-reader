package com.yaprak.reader.domain.statistics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow

const val MINUTES_PER_DAY = 24 * 60

/**
 * Optional device preference: a local time of day (minutes after midnight) for the daily reading
 * reminder. Absence means off. Like the goals, backups do not carry it.
 */
interface ReadingReminderRepository {
    val reminderMinuteOfDay: Flow<Int?>
    suspend fun saveReminderMinuteOfDay(minuteOfDay: Int?)
}

fun validateReadingReminder(minuteOfDay: Int?) {
    require(minuteOfDay == null || minuteOfDay in 0 until MINUTES_PER_DAY) { "Invalid reminder time" }
}

/** The next moment at or after [now] whose local wall time is [minuteOfDay] (skipping DST gaps sensibly). */
fun nextReadingReminderAt(now: Instant, minuteOfDay: Int, zone: ZoneId): Instant {
    validateReadingReminder(minuteOfDay)
    val today = now.atZone(zone).toLocalDate()
    fun at(date: LocalDate): ZonedDateTime =
        date.atStartOfDay(zone).withHour(minuteOfDay / 60).withMinute(minuteOfDay % 60)
    val candidate = at(today)
    return (if (candidate.toInstant().isAfter(now)) candidate else at(today.plusDays(1))).toInstant()
}

/**
 * What the reminder says, or null when it should stay quiet (today's goal already met, or some
 * reading happened today and there is no goal to measure against).
 */
fun readingReminderText(readTodayMillis: Long, dailyGoalMinutes: Int?): String? {
    val readMinutes = (readTodayMillis.coerceAtLeast(0) / 60_000).toInt()
    if (dailyGoalMinutes == null) return if (readTodayMillis > 0) null else "Bugün henüz okumadın"
    val left = dailyGoalMinutes - readMinutes
    if (left <= 0) return null
    return if (readTodayMillis <= 0) "Bugün henüz okumadın · hedefe $left dk"
        else "Bugünkü hedefe $left dk kaldı"
}

fun formatReminderTime(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
