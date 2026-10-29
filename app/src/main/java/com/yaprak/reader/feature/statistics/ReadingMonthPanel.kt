package com.yaprak.reader.feature.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Each heading, week and action is its own lazy item; no oversized interactive calendar item. */
internal fun LazyListScope.readingMonthItems(
    state: ReadingStatisticsState, requested: YearMonth, allowed: () -> Boolean,
    change: (expected: YearMonth, target: YearMonth) -> Unit,
) {
    val calendar = readingMonth(state, requested)
    item("month-title") {
        Text(stringResource(R.string.reading_calendar_month_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        Text(calendar.month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("tr-TR"))),
            Modifier.fillMaxWidth().testTag("statistics-month-label"), style = MaterialTheme.typography.titleLarge)
    }
    item("month-help") { Text(stringResource(R.string.reading_calendar_month_help), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall) }
    item("month-weekdays") {
        val headers = stringArrayResource(R.array.reading_calendar_weekdays_short)
        val names = stringArrayResource(R.array.reading_calendar_weekdays)
        Row(Modifier.fillMaxWidth()) {
            headers.forEachIndexed { index, title ->
                Box(Modifier.weight(1f).heightIn(min = 32.dp).clearAndSetSemantics { contentDescription = names[index] }, contentAlignment = Alignment.Center) {
                    Text(title, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    calendar.weeks.forEachIndexed { index, week -> item("month-week-$index") { ReadingMonthWeek(week, state.today) } }
    item("month-previous") {
        key(state.snapshot, state.today, state.revision, state.active) {
            ReadingMonthAction(stringResource(R.string.reading_calendar_previous), "statistics-month-previous", calendar.month,
                calendar.month > calendar.earliest && state.active, allowed) {
                change(calendar.month, calendar.month.minusMonths(1))
            }
        }
    }
    item("month-next") {
        key(state.snapshot, state.today, state.revision, state.active) {
            ReadingMonthAction(stringResource(R.string.reading_calendar_next), "statistics-month-next", calendar.month,
                calendar.month < calendar.latest && state.active, allowed) {
                change(calendar.month, calendar.month.plusMonths(1))
            }
        }
    }
}

@Composable
private fun ReadingMonthWeek(week: List<ReadingCalendarDay?>, today: LocalDate) {
    Row(Modifier.fillMaxWidth()) {
        week.forEach { day ->
            if (day == null) Spacer(Modifier.weight(1f).height(48.dp))
            else {
                val positive = day.elapsedMillis != null && day.elapsedMillis > 0
                val summary = day.elapsedMillis?.let { durationText(it) } ?: stringResource(R.string.reading_statistics_day_empty)
                val savedDescription = stringResource(R.string.reading_calendar_day_description, day.date.toString(), summary)
                val description = if (day.date == today) stringResource(R.string.reading_calendar_today_description, savedDescription) else savedDescription
                Box(Modifier.weight(1f).heightIn(min = 48.dp).padding(2.dp)
                    .testTag("calendar-cell-${day.date}")
                    .clearAndSetSemantics { contentDescription = description }
                    .then(if (positive) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape) else Modifier)
                    .then(if (day.date == today) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center) {
                    Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium,
                        color = if (positive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

private class ReadingMonthActionLease { var active = true }
@Composable
private fun ReadingMonthAction(label: String, tag: String, month: YearMonth, enabled: Boolean,
    allowed: () -> Boolean, action: () -> Unit) {
    key(month, enabled) {
        val lease = remember { ReadingMonthActionLease() }
        DisposableEffect(lease) { onDispose { lease.active = false } }
        Button(onClick = { if (lease.active && enabled && allowed()) action() },
            enabled = enabled && allowed(), modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Text(label, Modifier.fillMaxWidth())
        }
    }
}
