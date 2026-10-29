package com.yaprak.reader.feature.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.BookTextScope
import java.util.UUID

@Composable
internal fun LibraryTextScopePicker(state: LibraryTextSearchState, onChange: (BookTextScope?, Long) -> Unit) {
    val live by rememberUpdatedState(state)
    val rootLease = remember { ScopePickerLease() }
    DisposableEffect(rootLease) { onDispose { rootLease.active = false } }
    var request by remember(state.revision) { mutableStateOf<String?>(null) }
    val scope = state.scope
    val selected = if (scope == null) null else state.result?.books?.singleOrNull {
        it.book.id == scope.bookId && it.book.sha256 == scope.contentSha256
    }?.book
    val canPick = state.canChangeScope && !state.loading && state.failure == null && state.result != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.library_text_scope), style = MaterialTheme.typography.titleSmall)
        Text(if (scope == null) stringResource(R.string.library_text_scope_all)
            else selected?.title ?: stringResource(R.string.library_text_scope_changed), Modifier.fillMaxWidth())
        if (scope != null) Text(stringResource(R.string.library_text_scope_whole_book), style = MaterialTheme.typography.bodySmall)
        key(state.revision) {
            val revision = state.revision
            val lease = remember { ScopePickerLease() }
            DisposableEffect(lease) { onDispose { lease.active = false } }
            TextButton(onClick = {
                if (rootLease.active && lease.active && live.revision == revision && live.canChangeScope &&
                    !live.loading && live.result != null && live.failure == null) request = UUID.randomUUID().toString()
            }, enabled = canPick, modifier = Modifier.fillMaxWidth().testTag("library-text-scope-pick")) {
                Text(stringResource(R.string.library_text_scope_pick), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            if (scope != null) TextButton(onClick = {
                if (rootLease.active && lease.active && live.revision == revision && live.scope == scope &&
                    live.canChangeScope) onChange(null, revision)
            }, enabled = state.canChangeScope, modifier = Modifier.fillMaxWidth().testTag("library-text-scope-clear")) {
                Text(stringResource(R.string.library_text_scope_all), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
    val id = request
    if (id != null && canPick) key(id, state.revision) {
        val revision = state.revision
        val lease = remember { ScopePickerLease() }
        DisposableEffect(lease) { onDispose { lease.active = false } }
        fun mayAct() = rootLease.active && lease.active && request == id && live.revision == revision &&
            live.canChangeScope && !live.loading && live.failure == null && live.result != null
        fun close() { if (mayAct()) request = null }
        AlertDialog(onDismissRequest = ::close,
            title = { Text(stringResource(R.string.library_text_scope)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("library-text-scope-options"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item("all") {
                        TextButton(onClick = { if (mayAct()) { request = null; onChange(null, revision) } },
                            modifier = Modifier.fillMaxWidth().testTag("library-text-scope-option-all")) {
                            Text(stringResource(R.string.library_text_scope_all), Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                        }
                    }
                    items(state.result!!.books.filter { it.book.format == BookFormat.EPUB ||
                        it.state != com.yaprak.reader.domain.search.LibraryTextBookState.UNSUPPORTED }, key = { it.book.id }) { status ->
                        val book = status.book
                        key(book) {
                            TextButton(onClick = {
                                if (mayAct() && live.result?.books?.singleOrNull { it.book.id == book.id }?.book == book) {
                                    request = null
                                    onChange(BookTextScope(book.id, book.sha256), revision)
                                }
                            }, modifier = Modifier.fillMaxWidth().testTag("library-text-scope-option-${book.id}")) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(book.title, Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                                    book.authors?.let { Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = ::close,
                modifier = Modifier.fillMaxWidth().testTag("library-text-scope-close")) {
                Text(stringResource(R.string.library_text_scope_close), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            } })
    }
}

private class ScopePickerLease { var active = true }
