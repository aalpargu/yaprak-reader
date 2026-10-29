package com.yaprak.reader.domain.statistics

import kotlinx.coroutines.flow.Flow

const val MIN_DAILY_READING_GOAL_MINUTES = 1
const val MAX_DAILY_READING_GOAL_MINUTES = 1000

enum class DailyReadingGoalFailure { INVALID_VALUE }
class DailyReadingGoalException(val reason: DailyReadingGoalFailure) : Exception(reason.name)

/** Optional device preference. Absence means off; backups do not change it. */
interface DailyReadingGoalRepository {
    val dailyMinutes: Flow<Int?>
    suspend fun saveDailyMinutes(minutes: Int?)
}

fun validateDailyReadingGoal(minutes: Int?) {
    if (minutes != null && minutes !in MIN_DAILY_READING_GOAL_MINUTES..MAX_DAILY_READING_GOAL_MINUTES)
        throw DailyReadingGoalException(DailyReadingGoalFailure.INVALID_VALUE)
}
