package com.yaprak.reader.domain.statistics

import kotlinx.coroutines.flow.Flow

const val MIN_WEEKLY_READING_GOAL_MINUTES = 1
const val MAX_WEEKLY_READING_GOAL_MINUTES = 7000

enum class WeeklyReadingGoalFailure { INVALID_VALUE }
class WeeklyReadingGoalException(val reason: WeeklyReadingGoalFailure) : Exception(reason.name)

/** Optional device preference. Absence means off; backups do not change it. */
interface WeeklyReadingGoalRepository {
    val weeklyMinutes: Flow<Int?>
    suspend fun saveWeeklyMinutes(minutes: Int?)
}

fun validateWeeklyReadingGoal(minutes: Int?) {
    if (minutes != null && minutes !in MIN_WEEKLY_READING_GOAL_MINUTES..MAX_WEEKLY_READING_GOAL_MINUTES)
        throw WeeklyReadingGoalException(WeeklyReadingGoalFailure.INVALID_VALUE)
}
