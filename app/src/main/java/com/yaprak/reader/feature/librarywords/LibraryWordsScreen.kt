package com.yaprak.reader.feature.librarywords

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
import com.yaprak.reader.domain.vocabulary.LibraryWord

@Composable
internal fun LibraryWordsScreen(
    state: LibraryWordsState, onSearch: (String) -> Unit, onReload: (Long) -> Unit,
    onPractice: (Long, String) -> Unit, onOpen: (Long, LibraryWord) -> Unit, onBack: () -> Unit, onReview: () -> Unit = {},
) {
    Scaffold(bottomBar = {
        Surface { TextButton(onBack, Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(stringResource(R.string.library_words_back), Modifier.fillMaxWidth())
        } }
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("library-words-list"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("title") { Text(stringResource(R.string.library_words_title), style = MaterialTheme.typography.headlineMedium) }
            item("help") { Text(stringResource(R.string.library_words_help), style = MaterialTheme.typography.bodySmall) }

            item("review") { WordAction(stringResource(R.string.review_title), "word-review", state.revision, state.actionsEnabled) { onReview() } }
            item("query") {
                Column {
                    Text(stringResource(R.string.library_words_search), Modifier.fillMaxWidth())
                    OutlinedTextField(state.query, onSearch, Modifier.fillMaxWidth().testTag("library-words-search"), singleLine = true)
                }
            }
            when {
                state.loading -> item("loading") { CircularProgressIndicator(); Text(stringResource(R.string.library_words_loading)) }
                state.failed -> item("failed") { Text(stringResource(R.string.library_words_failed), color = MaterialTheme.colorScheme.error) }
                else -> {
                    item("count") { Text(stringResource(R.string.library_words_count, state.entries.size, state.totalCount)) }
                    if (state.feedback != null) item("feedback") {
                        Text(stringResource(if (state.feedback == "changed") R.string.library_words_changed else R.string.library_words_open_failed),
                            color = MaterialTheme.colorScheme.error)
                    }
                    val card = state.current
                    if (card != null) {
                        item("practice-scope") { Text(stringResource(R.string.library_words_practice_scope), style = MaterialTheme.typography.bodySmall) }
                        item("card") {
                            val cards = state.practice!!.cards(state.entries.map { it.word })
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.library_words_card_count, cards.indexOf(card.word) + 1, cards.size))
                                Text(card.bookTitle, style = MaterialTheme.typography.titleSmall)
                                Text(card.word.english, Modifier.fillMaxWidth().testTag("library-word-question"), style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                        item("answer") {
                            if (state.practice?.revealed == card.word)
                                Text(card.word.turkish, Modifier.fillMaxWidth().testTag("library-word-answer"), style = MaterialTheme.typography.titleLarge)
                            else WordAction(stringResource(R.string.library_words_reveal), "word-reveal", state.revision, state.actionsEnabled) {
                                onPractice(state.revision, "reveal")
                            }
                        }
                        listOf("previous" to R.string.library_words_previous, "next" to R.string.library_words_next,
                            "shuffle" to R.string.library_words_shuffle, "stop" to R.string.library_words_stop).forEach { (command, label) ->
                            item("practice-$command") { WordAction(stringResource(label), "word-$command", state.revision, state.actionsEnabled) {
                                onPractice(state.revision, command)
                            } }
                        }
                        item("card-book") { WordAction(stringResource(R.string.library_words_open), "word-open-${card.word.id}", state.revision, state.actionsEnabled) {
                            onOpen(state.revision, card)
                        } }
                    } else {
                        if (state.entries.isEmpty()) item("empty") {
                            Text(stringResource(if (state.query.isBlank()) R.string.library_words_empty else R.string.library_words_no_matches))
                        } else item("practice-start") { WordAction(stringResource(R.string.library_words_start), "word-start", state.revision, state.actionsEnabled) {
                            onPractice(state.revision, "start")
                        } }
                        items(state.entries, key = { it.word.id }) { entry ->
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(entry.bookTitle, style = MaterialTheme.typography.titleSmall)
                                entry.bookAuthors?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                Text(entry.word.english, style = MaterialTheme.typography.titleLarge)
                                Text(entry.word.turkish)
                                WordAction(stringResource(R.string.library_words_open), "word-open-${entry.word.id}", state.revision, state.actionsEnabled) {
                                    onOpen(state.revision, entry)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
            item("reload") { WordAction(stringResource(R.string.library_words_reload), "word-reload", state.revision, state.active && !state.busy) {
                onReload(state.revision)
            } }
        }
    }
}

private class WordActionLease { var active = true }
@Composable
private fun WordAction(label: String, tag: String, revision: Long, enabled: Boolean, action: () -> Unit) {
    key(tag, revision) {
        val lease = remember { WordActionLease() }
        DisposableEffect(lease) { onDispose { lease.active = false } }
        TextButton(onClick = { if (lease.active && enabled) action() }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Text(label, Modifier.fillMaxWidth())
        }
    }
}
