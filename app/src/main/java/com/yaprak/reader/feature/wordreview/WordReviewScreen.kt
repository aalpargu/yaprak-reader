package com.yaprak.reader.feature.wordreview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R
import com.yaprak.reader.domain.vocabulary.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun WordReviewScreen(state: WordReviewState, onCommand: (Long, String) -> Unit,
    onRate: (Long, WordRating) -> Unit, onReload: (Long) -> Unit, onRetry: (Long) -> Unit, onBack: () -> Unit) {
    Scaffold(bottomBar = { Surface { TextButton(onBack, Modifier.fillMaxWidth().navigationBarsPadding()) {
        Text(stringResource(R.string.review_back), Modifier.fillMaxWidth())
    } } }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("review-list"), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("title") { Text(stringResource(R.string.review_title), style = MaterialTheme.typography.headlineMedium) }
            item("help") { Text(stringResource(R.string.review_help), style = MaterialTheme.typography.bodySmall) }
            if (state.loading || state.busy) item("busy") { CircularProgressIndicator(); Text(stringResource(R.string.review_loading)) }
            state.receipt?.let { receipt -> item("receipt") {
                Text(stringResource(R.string.review_saved, reviewDate(receipt.dueAt, receipt.zoneId)), Modifier.testTag("review-receipt"))
            } }
            state.failure?.let { failure -> item("failure") {
                Text(stringResource(when (failure) {
                    WordReviewFailure.STALE -> R.string.review_stale
                    WordReviewFailure.CLOCK -> R.string.review_clock
                    WordReviewFailure.LIMIT -> R.string.review_limit
                    else -> R.string.review_failed
                }), color = MaterialTheme.colorScheme.error)
            } }
            state.snapshot?.let { snapshot ->
                item("counts") { Text(stringResource(R.string.review_counts, snapshot.dueCount, snapshot.newCount, snapshot.totalWords)) }
                val card = state.current
                if (state.practicing) {
                    if (card == null) item("done") { Text(stringResource(R.string.review_done)) }
                    else {
                        item("card") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(card.entry.bookTitle, style = MaterialTheme.typography.titleSmall)
                            Text(card.entry.word.english, Modifier.fillMaxWidth().testTag("review-question"), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(if (card.currentSchedule == null) R.string.review_new else R.string.review_due))
                        } }
                        if (state.revealed) {
                            item("answer") { Text(card.entry.word.turkish, Modifier.fillMaxWidth().testTag("review-answer"), style = MaterialTheme.typography.titleLarge) }
                            WordRating.entries.forEach { rating -> item("rating-$rating") {
                                ReviewAction(stringResource(ratingLabel(rating)), "review-rate-$rating", state.revision,
                                    state.actionsEnabled && state.pending == null) { onRate(state.revision, rating) }
                            } }
                        } else item("reveal") {
                            ReviewAction(stringResource(R.string.review_reveal), "review-reveal", state.revision, state.actionsEnabled) {
                                onCommand(state.revision, "reveal")
                            }
                        }
                    }
                    item("stop") { ReviewAction(stringResource(R.string.review_stop), "review-stop", state.revision,
                        state.actionsEnabled && state.pending == null) { onCommand(state.revision, "stop") } }
                } else item("start") {
                    ReviewAction(stringResource(R.string.review_start), "review-start", state.revision,
                        state.actionsEnabled && snapshot.cards.isNotEmpty()) { onCommand(state.revision, "start") }
                }
                item("scope") { Text(stringResource(R.string.review_scope, snapshot.cards.size, snapshot.dueCount), style = MaterialTheme.typography.bodySmall) }
                if (!state.practicing) {
                    item("history-title") { Text(stringResource(R.string.review_history, snapshot.history.size, snapshot.historyCount), style = MaterialTheme.typography.titleLarge) }
                    if (snapshot.history.isEmpty()) item("no-history") { Text(stringResource(R.string.review_no_history)) }
                    items(snapshot.history, key = { "history-${it.event.id}" }) { history ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(history.bookTitle, style = MaterialTheme.typography.titleSmall)
                            Text(history.event.english, style = MaterialTheme.typography.titleLarge)
                            Text(history.event.turkish)
                            Text(stringResource(ratingLabel(history.event.rating)))
                            Text(stringResource(R.string.review_history_date, reviewDate(history.event.reviewedAt, history.event.zoneId),
                                reviewDate(history.event.dueAt, history.event.zoneId)))
                            if (history.previousContent) Text(stringResource(R.string.review_previous_content))
                            HorizontalDivider()
                        }
                    }
                }
            }
            if (state.pending != null && !state.busy) item("retry") {
                ReviewAction(stringResource(R.string.review_retry), "review-retry", state.revision, state.actionsEnabled) { onRetry(state.revision) }
            }
            item("reload") { ReviewAction(stringResource(R.string.review_reload), "review-reload", state.revision, state.active && !state.busy) { onReload(state.revision) } }
        }
    }
}
private fun ratingLabel(rating: WordRating) = when (rating) {
    WordRating.AGAIN -> R.string.review_again
    WordRating.HARD -> R.string.review_hard
    WordRating.GOOD -> R.string.review_good
    WordRating.EASY -> R.string.review_easy
}
private fun reviewDate(value: Long, zone: String) = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm VV")
    .format(Instant.ofEpochMilli(value).atZone(ZoneId.of(zone)))
private class ReviewActionLease { var active = true }
@Composable
private fun ReviewAction(label: String, tag: String, revision: Long, enabled: Boolean, action: () -> Unit) {
    key(tag, revision) {
        val lease = remember { ReviewActionLease() }
        DisposableEffect(lease) { onDispose { lease.active = false } }
        TextButton(onClick = { if (lease.active && enabled) action() }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(label, Modifier.fillMaxWidth()) }
    }
}
