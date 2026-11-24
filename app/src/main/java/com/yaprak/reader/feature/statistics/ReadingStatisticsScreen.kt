package com.yaprak.reader.feature.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
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
    onWeeklyRetry: ((Long) -> Unit)? = null, reminder: (@Composable () -> Unit)? = null) {
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
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton({ if (mayAct()) onBack() }, Modifier.testTag("statistics-back"), enabled = state.active) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.reading_statistics_back))
                }
                Text(stringResource(R.string.reading_statistics_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton({ infoOpen = !infoOpen }) {
                    Icon(androidx.compose.material.icons.Icons.Default.Info, contentDescription = "Nasıl ölçülür?")
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).testTag("statistics-body"),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                if (infoOpen) item("info") {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.reading_statistics_help), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.reading_statistics_scope), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.reading_statistics_dates_help), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.reading_statistics_zone, state.zoneId), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.reading_streak_help), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.reading_statistics_completed_help), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
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
                        item("tiles") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    StatTile("Bugün", durationText(state.todayMillis), Modifier.weight(1f).testTag("statistics-today"))
                                    StatTile("Son 7 gün", durationText(state.lastSevenMillis), Modifier.weight(1f).testTag("statistics-week"))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    // A series is shown only from a current, verified snapshot; never a former value.
                                    if (streak != null) StatTile("Okuma serisi", "${streak.currentDays} gün",
                                        Modifier.weight(1f).testTag("statistics-streak-current"),
                                        "En uzun: ${streak.longestDays} gün")
                                    else StatTile("Okuma serisi", "—", Modifier.weight(1f))
                                    StatTile("Toplam", durationText(state.snapshot.totalMillis), Modifier.weight(1f).testTag("statistics-total"),
                                        "Bitirilen kitap: ${state.completedBookCount}")
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    StatTile("Günlük ortalama", durationText(state.dailyAverageMillis), Modifier.weight(1f).testTag("statistics-average"),
                                        "son 7 gün")
                                    StatTile("Okunan gün", "${state.readingDaysLastSeven} / 7", Modifier.weight(1f).testTag("statistics-reading-days"),
                                        streak?.lastReadingDate?.let { "Son: $it" })
                                }
                                // Pages appear only once the reader has counted some; older time has no page data.
                                state.lastSevenPages?.let { pages ->
                                    StatTile("Okunan sayfa · son 7 gün", "$pages", Modifier.fillMaxWidth().testTag("statistics-pages"),
                                        state.todayPages?.let { "Bugün: $it" })
                                }
                                if (state.snapshot.totalMillis == 0L)
                                    Text(stringResource(R.string.reading_statistics_empty), Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                goalState?.let { goal -> item(key = "daily-goal") {
                    GoalCard {
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
                    }
                } }
                reminder?.let { panel -> item(key = "reading-reminder") { GoalCard { panel() } } }
                weeklyState?.let { goal -> item(key = "weekly-goal") {
                    GoalCard {
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
                    }
                } }
                onCompletionHistory?.let { action -> item {
                    OutlinedButton(onClick = { if (mayAct()) action() }, enabled = mayAct(),
                        modifier = Modifier.fillMaxWidth().testTag("statistics-completion-history")) { Text("Kitap bitirme geçmişi") }
                } }
                if (state.snapshot != null && !state.loading && state.failure == null) {
                        item {
                            val renderedExpanded = expanded
                            val snapshot = state.snapshot
                            val today = state.today
                            val revision = state.revision
                            key(renderedExpanded, snapshot, today, revision, state.active) {
                                val actionLease = remember { ReadingStatisticsScreenLease() }
                                DisposableEffect(actionLease) { onDispose { actionLease.active = false } }
                                OutlinedButton({
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
                                    Text(listOfNotNull(durationText(book.totalMillis), book.pages?.let { "$it sayfa" })
                                        .joinToString(" · "), Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
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

@Composable private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, caption: String? = null) {
    // One accessibility node per tile, read as "label, value, caption".
    Surface(modifier.semantics(mergeDescendants = true) {}, color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge)
            if (caption != null) Text(caption, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun GoalCard(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Box(Modifier.fillMaxWidth().padding(14.dp)) { content() }
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
