package com.yaprak.reader.feature.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R
import com.yaprak.reader.domain.statistics.ReadingDurationFailure
import java.time.YearMonth

@Composable
internal fun ReadingStatisticsScreen(state: ReadingStatisticsState, onRetry: (Long) -> Unit, onBack: () -> Unit,
    goalState: DailyReadingGoalState? = null, onGoalOpen: ((Long) -> Unit)? = null,
    onGoalRetry: ((Long) -> Unit)? = null, onCompletionHistory: (() -> Unit)? = null,
    weeklyState: WeeklyReadingGoalState? = null, onWeeklyOpen: ((Long) -> Unit)? = null,
    onWeeklyRetry: ((Long) -> Unit)? = null) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var chosenMonth by rememberSaveable { mutableStateOf<String?>(null) }
    val liveState by rememberUpdatedState(state)
    val liveWeekly by rememberUpdatedState(weeklyState)
    val liveWeeklyOpen by rememberUpdatedState(onWeeklyOpen)
    val liveWeeklyRetry by rememberUpdatedState(onWeeklyRetry)
    val liveGoal by rememberUpdatedState(goalState)
    val liveGoalOpen by rememberUpdatedState(onGoalOpen)
    val liveGoalRetry by rememberUpdatedState(onGoalRetry)
    val lease = remember { ReadingStatisticsScreenLease() }
    DisposableEffect(lease) { lease.active = true; onDispose { lease.active = false } }
    fun mayAct() = lease.active && liveState.active && liveGoal?.editor == null && liveWeekly?.editor == null &&
        liveGoal?.saving != true && liveWeekly?.saving != true
    val streak = remember(state.snapshot, state.today, state.active, state.loading, state.failure) { state.readingStreak }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("statistics-body"),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                item {
                    Text(stringResource(R.string.reading_statistics_title), Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.reading_statistics_help), Modifier.fillMaxWidth())
                    Text(stringResource(R.string.reading_statistics_scope), Modifier.fillMaxWidth())
                }
                onCompletionHistory?.let { action -> item {
                    TextButton(onClick = { if (mayAct()) action() }, enabled = mayAct(),
                        modifier = Modifier.fillMaxWidth().testTag("statistics-completion-history")) { Text("Kitap bitirme geçmişi") }
                } }
                goalState?.let { goal -> item(key = "daily-goal") {
                    DailyReadingGoalPanel(state, goal,
                        onOpen = onGoalOpen?.let { callback -> { revision ->
                            val current = liveGoal
                            if (mayAct() && liveGoalOpen === callback && current?.active == true &&
                                current.revision == revision && current.known && !current.saving && current.editor == null)
                                callback(revision)
                        } },
                        onRetry = onGoalRetry?.let { callback -> { revision ->
                            val current = liveGoal
                            if (mayAct() && liveGoalRetry === callback && current?.active == true &&
                                current.revision == revision && !current.saving && current.readFailure != null)
                                callback(revision)
                        } })
                } }
                weeklyState?.let { goal -> item(key = "weekly-goal") {
                    WeeklyReadingGoalPanel(state, goal,
                        onOpen = onWeeklyOpen?.let { callback -> { revision ->
                            val current = liveWeekly
                            if (mayAct() && liveWeeklyOpen === callback && current?.active == true &&
                                current.revision == revision && current.known && !current.saving && current.editor == null)
                                callback(revision)
                        } },
                        onRetry = onWeeklyRetry?.let { callback -> { revision ->
                            val current = liveWeekly
                            if (mayAct() && liveWeeklyRetry === callback && current?.active == true &&
                                current.revision == revision && !current.saving && current.readFailure != null)
                                callback(revision)
                        } })
                } }
                when {
                    state.loading -> item { Text(stringResource(R.string.reading_statistics_loading), Modifier.fillMaxWidth()) }
                    state.failure != null -> item {
                        Text(stringResource(when (state.failure) {
                            ReadingDurationFailure.RECOVERY -> R.string.reading_statistics_recovery
                            ReadingDurationFailure.INVALID_RECORD -> R.string.reading_statistics_invalid
                            ReadingDurationFailure.LIMIT -> R.string.reading_statistics_limit
                            ReadingDurationFailure.STALE_BOOK -> R.string.reading_statistics_stale
                            else -> R.string.reading_statistics_failed
                        }), Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                        val revision = state.revision
                        key(revision) {
                            Button({
                                if (mayAct() && liveState.revision == revision && !liveState.loading && liveState.failure != null)
                                    onRetry(revision)
                            }, Modifier.fillMaxWidth().testTag("statistics-retry"), enabled = state.active) {
                                Text(stringResource(R.string.reading_statistics_retry), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            }
                        }
                    }
                    state.snapshot != null -> {
                        item {
                            Statistic(stringResource(R.string.reading_statistics_today, state.today.toString()), state.todayMillis, "statistics-today")
                            Statistic(stringResource(R.string.reading_statistics_week), state.lastSevenMillis, "statistics-week")
                            Text(stringResource(R.string.reading_statistics_period, state.today.minusDays(6).toString(),
                                state.today.toString()), Modifier.fillMaxWidth())
                            Statistic(stringResource(R.string.reading_statistics_total), state.snapshot.totalMillis, "statistics-total")
                            Text(stringResource(R.string.reading_statistics_zone, state.zoneId), Modifier.fillMaxWidth())
                            Text(stringResource(R.string.reading_statistics_dates_help), Modifier.fillMaxWidth())
                            if (state.snapshot.totalMillis == 0L)
                                Text(stringResource(R.string.reading_statistics_empty), Modifier.fillMaxWidth())
                        }
                        item("daily-average") {
                            Statistic(stringResource(R.string.reading_statistics_average), state.dailyAverageMillis, "statistics-average")
                            Text(stringResource(R.string.reading_statistics_average_help), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                        }
                        item("reading-days") {
                            Text(stringResource(R.string.reading_statistics_days, state.readingDaysLastSeven),
                                Modifier.fillMaxWidth().testTag("statistics-reading-days"), style = MaterialTheme.typography.titleMedium)
                        }
                        streak?.let { value ->
                            item("current-streak") {
                                Text(stringResource(R.string.reading_streak_current, value.currentDays),
                                    Modifier.fillMaxWidth().testTag("statistics-streak-current"), style = MaterialTheme.typography.titleMedium)
                            }
                            item("longest-streak") {
                                Text(stringResource(R.string.reading_streak_longest, value.longestDays),
                                    Modifier.fillMaxWidth().testTag("statistics-streak-longest"), style = MaterialTheme.typography.titleMedium)
                            }
                            value.lastReadingDate?.let { last -> item("last-reading-day") {
                                Text(stringResource(R.string.reading_streak_last, last.toString()),
                                    Modifier.fillMaxWidth().testTag("statistics-streak-last"))
                            } }
                            item("streak-help") {
                                Text(stringResource(R.string.reading_streak_help), Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item("completed-books") {
                            Text(stringResource(R.string.reading_statistics_completed, state.completedBookCount),
                                Modifier.fillMaxWidth().testTag("statistics-completed"), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.reading_statistics_completed_help), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                        }
                        item {
                            val renderedExpanded = expanded
                            val snapshot = state.snapshot
                            val today = state.today
                            val revision = state.revision
                            key(renderedExpanded, snapshot, today, revision, state.active) {
                                val actionLease = remember { ReadingStatisticsScreenLease() }
                                DisposableEffect(actionLease) { onDispose { actionLease.active = false } }
                                Button({
                                    if (actionLease.active && mayAct() && !liveState.loading && liveState.failure == null &&
                                        liveState.snapshot === snapshot && liveState.today == today && liveState.revision == revision &&
                                        expanded == renderedExpanded) expanded = !renderedExpanded
                                }, Modifier.fillMaxWidth().testTag("statistics-calendar-toggle"), enabled = state.active) {
                                    Text(stringResource(if (renderedExpanded) R.string.reading_statistics_calendar_hide
                                        else R.string.reading_statistics_calendar_show), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                            }
                        }
                        if (expanded) {
                            val requested = chosenMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.from(state.today)
                            val snapshot = state.snapshot
                            val today = state.today
                            val revision = state.revision
                            readingMonthItems(state, requested, ::mayAct) { expected, target ->
                                val current = liveState
                                if (mayAct() && expanded && !current.loading && current.failure == null &&
                                    current.snapshot != null && current.snapshot === snapshot && current.today == today && current.revision == revision) {
                                    val requestedCurrent = chosenMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.from(current.today)
                                    val actual = readingMonth(current, requestedCurrent)
                                    if (actual.month == expected && target in actual.earliest..actual.latest)
                                        chosenMonth = target.toString()
                                }
                            }
                            item { Text(stringResource(R.string.reading_statistics_calendar_title), Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.titleMedium) }
                            items(state.calendar, key = { "day:${it.date}" }) { day ->
                                Column(Modifier.fillMaxWidth().testTag("statistics-day-${day.date}")) {
                                    Text(day.date.toString(), Modifier.fillMaxWidth())
                                    Text(day.elapsedMillis?.let { durationText(it) }
                                        ?: stringResource(R.string.reading_statistics_day_empty), Modifier.fillMaxWidth())
                                }
                            }
                        }
                        item { Text(stringResource(R.string.reading_statistics_books), Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.titleMedium) }
                        items(state.snapshot.books.filter { it.totalMillis > 0 }, key = { "book:${it.book.id}" }) { book ->
                            Card(Modifier.fillMaxWidth().testTag("statistics-book-${book.book.id}")) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(book.book.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                                    book.book.authors?.let { Text(it, Modifier.fillMaxWidth()) }
                                    Text(durationText(book.totalMillis), Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
            Button({ if (mayAct()) onBack() }, Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("statistics-back"), enabled = state.active) {
                Text(stringResource(R.string.reading_statistics_back), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun DailyReadingGoalPanel(statistics: ReadingStatisticsState, goal: DailyReadingGoalState,
    onOpen: ((Long) -> Unit)?, onRetry: ((Long) -> Unit)?) {
    Column(Modifier.fillMaxWidth().testTag("statistics-daily-goal"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.daily_goal_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.daily_goal_device), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        when {
            goal.loading -> Text(stringResource(R.string.daily_goal_loading), Modifier.fillMaxWidth())
            goal.readFailure != null -> {
                Text(stringResource(if (goal.readFailure == DailyReadingGoalReadFailure.INVALID_VALUE)
                    R.string.daily_goal_stored_invalid else R.string.daily_goal_load_failed), Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error)
                key(goal.revision) {
                    Button({ onRetry?.invoke(goal.revision) }, enabled = goal.active && !goal.saving && onRetry != null,
                        modifier = Modifier.fillMaxWidth().testTag("statistics-goal-retry")) {
                        Text(stringResource(R.string.daily_goal_retry), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }
            else -> {
                val minutes = goal.savedMinutes
                if (minutes == null) Text(stringResource(R.string.daily_goal_off), Modifier.fillMaxWidth())
                else {
                    Text(stringResource(R.string.daily_goal_saved, minutes), Modifier.fillMaxWidth())
                    if (!statistics.active || statistics.loading || statistics.failure != null || statistics.snapshot == null)
                        Text(stringResource(R.string.daily_goal_duration_unavailable), Modifier.fillMaxWidth())
                    else {
                        val progress = dailyReadingGoalProgress(minutes, statistics.todayMillis)
                        Text(stringResource(R.string.daily_goal_progress, progress.percent), Modifier.fillMaxWidth())
                        Text(stringResource(R.string.daily_goal_today, durationText(statistics.todayMillis)), Modifier.fillMaxWidth())
                        if (progress.achieved) Text(stringResource(R.string.daily_goal_achieved), Modifier.fillMaxWidth())
                        else Text(stringResource(R.string.daily_goal_remaining, durationText(progress.remainingMillis)), Modifier.fillMaxWidth())
                    }
                }
                if (goal.writeFailed) Text(stringResource(R.string.daily_goal_previous_save_failed), Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error)
                if (goal.saving) Text(stringResource(R.string.daily_goal_saving), Modifier.fillMaxWidth())
                key(goal.revision) {
                    Button({ onOpen?.invoke(goal.revision) }, enabled = goal.active && !goal.saving &&
                        goal.editor == null && onOpen != null,
                        modifier = Modifier.fillMaxWidth().testTag("statistics-goal-open")) {
                        Text(stringResource(R.string.daily_goal_edit), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeklyReadingGoalPanel(statistics: ReadingStatisticsState, goal: WeeklyReadingGoalState,
    onOpen: ((Long) -> Unit)?, onRetry: ((Long) -> Unit)?) {
    Column(Modifier.fillMaxWidth().testTag("statistics-weekly-goal"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.weekly_goal_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.weekly_goal_period, statistics.weekStart.toString(), statistics.weekStart.plusDays(6).toString()), Modifier.fillMaxWidth())
        Text(stringResource(R.string.weekly_goal_device), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        when {
            goal.loading -> Text(stringResource(R.string.weekly_goal_loading), Modifier.fillMaxWidth())
            goal.readFailure != null -> {
                Text(stringResource(if (goal.readFailure == WeeklyReadingGoalReadFailure.INVALID_VALUE)
                    R.string.weekly_goal_stored_invalid else R.string.weekly_goal_load_failed), Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error)
                key(goal.revision) {
                    Button({ onRetry?.invoke(goal.revision) }, enabled = goal.active && !goal.saving && onRetry != null,
                        modifier = Modifier.fillMaxWidth().testTag("statistics-weekly-goal-retry")) {
                        Text(stringResource(R.string.weekly_goal_retry), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }
            else -> {
                val minutes = goal.savedMinutes
                if (minutes == null) Text(stringResource(R.string.weekly_goal_off), Modifier.fillMaxWidth())
                else {
                    Text(pluralStringResource(R.plurals.weekly_goal_saved, minutes, minutes), Modifier.fillMaxWidth())
                    if (!statistics.active || statistics.loading || statistics.failure != null || statistics.snapshot == null)
                        Text(stringResource(R.string.weekly_goal_duration_unavailable), Modifier.fillMaxWidth())
                    else {
                        val progress = weeklyReadingGoalProgress(minutes, statistics.currentWeekMillis)
                        Text(stringResource(R.string.weekly_goal_progress, progress.percent), Modifier.fillMaxWidth())
                        Text(stringResource(R.string.weekly_goal_today, durationText(statistics.currentWeekMillis)), Modifier.fillMaxWidth())
                        if (progress.achieved) Text(stringResource(R.string.weekly_goal_achieved), Modifier.fillMaxWidth())
                        else Text(stringResource(R.string.weekly_goal_remaining, durationText(progress.remainingMillis)), Modifier.fillMaxWidth())
                    }
                }
                if (goal.writeFailed) Text(stringResource(R.string.weekly_goal_previous_save_failed), Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error)
                if (goal.saving) Text(stringResource(R.string.weekly_goal_saving), Modifier.fillMaxWidth())
                key(goal.revision) {
                    Button({ onOpen?.invoke(goal.revision) }, enabled = goal.active && !goal.saving &&
                        goal.editor == null && onOpen != null,
                        modifier = Modifier.fillMaxWidth().testTag("statistics-weekly-goal-open")) {
                        Text(stringResource(R.string.weekly_goal_edit), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable private fun Statistic(label: String, millis: Long, tag: String) {
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        Text(durationText(millis), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall)
    }
}

private class ReadingStatisticsScreenLease { var active = true }

@Composable internal fun durationText(millis: Long): String {
    if (millis in 1..999) return stringResource(R.string.reading_statistics_under_second)
    val value = readingDurationParts(millis)
    return when {
        value.hours > 0 -> stringResource(R.string.reading_statistics_hours, value.hours, value.minutes, value.seconds)
        value.minutes > 0 -> stringResource(R.string.reading_statistics_minutes, value.minutes, value.seconds)
        else -> stringResource(R.string.reading_statistics_seconds, value.seconds)
    }
}
