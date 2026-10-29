package com.yaprak.reader.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R

@Composable
internal fun ReadingTimeStatus(estimate: ReadingTimeEstimate) {
    val label = when (estimate) {
        ReadingTimeEstimate.Measuring -> stringResource(R.string.reading_time_measuring)
        ReadingTimeEstimate.EndPosition -> stringResource(R.string.reading_time_end_position)
        is ReadingTimeEstimate.Remaining -> {
            stringResource(R.string.reading_time_remaining, readingDurationLabel(estimate.minutes))
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("reading-time-status")) {
        Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge)
        (estimate as? ReadingTimeEstimate.Remaining)?.chapterMinutes?.let { minutes ->
            val chapter = if (minutes == 0) stringResource(R.string.reading_time_chapter_end_position)
                else stringResource(R.string.reading_time_chapter_remaining, readingDurationLabel(minutes))
            Text(chapter, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge)
        }
        Text(stringResource(R.string.reading_time_session_note), Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun readingDurationLabel(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> stringResource(R.string.reading_time_minutes, minutes)
        minutes == 0 -> stringResource(R.string.reading_time_hours, hours)
        else -> stringResource(R.string.reading_time_hours_minutes, hours, minutes)
    }
}
