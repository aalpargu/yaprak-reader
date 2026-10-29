package com.yaprak.reader.feature.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.LibraryTextBookState
import com.yaprak.reader.domain.search.BookTextScope
import com.yaprak.reader.domain.search.LibraryTextHit
import com.yaprak.reader.domain.search.LibraryTextIndexFailure
import com.yaprak.reader.domain.search.MAX_LIBRARY_TEXT_RESULTS
import com.yaprak.reader.domain.search.supportsBookSourceRetrieval

@Composable
internal fun LibraryTextSearchScreen(
    state: LibraryTextSearchState,
    onQueryChange: (String) -> Unit,
    onSubmit: (String, Long) -> Unit,
    onRetry: (Long) -> Unit,
    onRebuild: (LibraryTextBuildPhase) -> Unit,
    onCancelBuild: (Long) -> Unit,
    onOpen: (LibraryTextHit) -> Unit,
    onBack: () -> Unit,
    onScopeChange: (BookTextScope?, Long) -> Unit = { _, _ -> },
    onAssistant: (BookTextScope, String, Long) -> Unit = { _, _, _ -> },
) {
    val live by rememberUpdatedState(state)
    val lease = remember { LibraryTextScreenLease() }
    DisposableEffect(lease) { lease.active = true; onDispose { lease.active = false } }
    fun mayAct() = lease.active && live.active
    var indexTab by rememberSaveable { mutableStateOf(false) }
    Scaffold(bottomBar = {
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center) {
            TextButton(onClick = { if (mayAct()) onBack() }, modifier = Modifier.fillMaxWidth().testTag("library-text-back")) {
                Text(stringResource(R.string.library_text_back), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 900.dp).fillMaxSize().testTag("library-text-list"),
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item("title") { Text(stringResource(R.string.library_text_title), style = MaterialTheme.typography.headlineMedium) }
                item("help") { Text(stringResource(R.string.library_text_help), style = MaterialTheme.typography.bodySmall) }
                item("tabs") {
                    Column {
                        TextButton(onClick = { if (mayAct()) indexTab = false }, enabled = state.active,
                            modifier = Modifier.fillMaxWidth().testTag("library-text-results-tab").semantics { selected = !indexTab }) {
                            Text(stringResource(R.string.library_text_results_tab), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                fontWeight = if (!indexTab) FontWeight.Bold else FontWeight.Normal)
                        }
                        TextButton(onClick = { if (mayAct()) indexTab = true }, enabled = state.active,
                            modifier = Modifier.fillMaxWidth().testTag("library-text-index-tab").semantics { selected = indexTab }) {
                            Text(stringResource(R.string.library_text_index_tab), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                fontWeight = if (indexTab) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                if (indexTab) {
                    item("build-help") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(if (state.scope == null) R.string.library_text_rebuild_help else R.string.library_text_selected_rebuild_help), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.library_text_limits), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item("build") {
                        LibraryTextBuildPanel(state, mayAct = ::mayAct, currentState = { live },
                            onRebuild = onRebuild, onCancel = onCancelBuild)
                    }
                } else {
                    item("scope") { LibraryTextScopePicker(state, onScopeChange) }
                    item("query") {
                        val query = state.query
                        val revision = state.revision
                        fun submit() {
                            if (mayAct() && live.canSearch && live.query == query && live.revision == revision)
                                onSubmit(query, revision)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val queryLabel = stringResource(R.string.library_text_query)
                            // Floating labels are constrained to a single Material label line.
                            // Keep the accessible name in a normally measured, wrapping sibling.
                            Text(queryLabel, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(value = query, onValueChange = { if (mayAct()) onQueryChange(it) },
                                modifier = Modifier.fillMaxWidth().testTag("library-text-query")
                                    .semantics { contentDescription = queryLabel }, singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { submit() }))
                            Text(stringResource(R.string.library_text_query_help), style = MaterialTheme.typography.bodySmall)
                            key(query, revision) {
                                TextButton(onClick = ::submit, enabled = state.canSearch,
                                    modifier = Modifier.fillMaxWidth().testTag("library-text-search")) {
                                    Text(stringResource(R.string.library_text_search), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                                if (query.isNotEmpty()) TextButton(onClick = {
                                    if (mayAct() && live.query == query && live.revision == revision) onQueryChange("")
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.library_text_clear), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    if (state.scope != null && state.actionsEnabled && state.result?.hits?.isNotEmpty() == true &&
                        state.result.books.singleOrNull { it.book.id == state.scope.bookId && it.book.sha256 == state.scope.contentSha256 }
                            ?.book?.format?.let { supportsBookSourceRetrieval(it) } == true) item("assistant") {
                        val scope = state.scope
                        val query = state.searchedQuery!!
                        val revision = state.revision
                        key(scope, query, revision, state.result) {
                        val actionLease = remember { LibraryTextScreenLease() }
                        DisposableEffect(actionLease) { onDispose { actionLease.active = false } }
                        TextButton(onClick = {
                            if (actionLease.active && mayAct() && live.actionsEnabled && live.scope == scope && live.searchedQuery == query && live.revision == revision)
                                onAssistant(scope, query, revision)
                        }, enabled = state.actionsEnabled, modifier = Modifier.fillMaxWidth().testTag("library-text-assistant")) {
                            Text(stringResource(R.string.assistant_title), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                        }
                    }
                    if (state.building) item("building-info") {
                        Text(stringResource(if ((state.build as? LibraryTextBuildPhase.Running)?.stopping == true)
                            R.string.library_text_stopping else R.string.library_text_build_starting),
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                when {
                    state.loading -> item("loading") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.library_text_loading), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                    state.failure != null -> item("failed") {
                        val revision = state.revision
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(failureText(state.failure)), color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            key(revision) {
                                TextButton(onClick = {
                                    if (mayAct() && !live.building && live.revision == revision) onRetry(revision)
                                }, enabled = state.active && !state.building, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.library_text_retry), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    else -> {
                        val result = state.result
                        if (result != null) {
                            item("coverage") {
                                val supported = result.books.count { it.state != LibraryTextBookState.UNSUPPORTED }
                                val ready = result.books.count { it.state == LibraryTextBookState.READY }
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.library_text_coverage, ready, supported))
                                    if (ready < supported) Text(stringResource(R.string.library_text_incomplete), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (indexTab) {
                                if (result.books.isEmpty()) item("no-books") { Text(stringResource(R.string.library_text_no_books)) }
                                items(result.books, key = { "book:${it.book.id}" }) { status ->
                                    Column(Modifier.fillMaxWidth().testTag("library-text-book-${status.book.id}"),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(status.book.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                                        status.book.authors?.let { Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall) }
                                        Text(when (status.state) {
                                            LibraryTextBookState.READY -> stringResource(R.string.library_text_status_ready, status.chunkCount)
                                            LibraryTextBookState.NOT_INDEXED -> stringResource(R.string.library_text_status_not_indexed)
                                            LibraryTextBookState.UNSUPPORTED -> stringResource(if (status.book.format == BookFormat.PDF)
                                                R.string.library_text_status_unsupported else if (status.book.format == BookFormat.TXT)
                                                R.string.library_text_status_unsupported_txt else if (status.book.format.comic)
                                                R.string.library_text_cbz_unsupported else if (status.book.format == BookFormat.FB2)
                                                R.string.library_text_fb2_unsupported else if (status.book.format == BookFormat.DOCX)
                                                R.string.library_text_docx_unsupported else R.string.library_text_status_unsupported_epub)
                                            LibraryTextBookState.FAILED -> stringResource(R.string.library_text_status_failed)
                                            LibraryTextBookState.LIMIT -> stringResource(R.string.library_text_status_limit)
                                            LibraryTextBookState.NO_TEXT -> stringResource(if (status.book.format == BookFormat.DOCX)
                                                R.string.library_text_docx_no_text else R.string.library_text_status_no_text)
                                        }, Modifier.fillMaxWidth())
                                        HorizontalDivider()
                                    }
                                }
                            } else if (state.searchedQuery == null) {
                                item("idle") { Text(stringResource(R.string.library_text_idle)) }
                            } else {
                                item("count") {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(stringResource(R.string.library_text_count, result.totalMatches), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                        if (result.truncated) Text(stringResource(R.string.library_text_limit, MAX_LIBRARY_TEXT_RESULTS, result.totalMatches),
                                            style = MaterialTheme.typography.bodySmall)
                                        Text(stringResource(R.string.library_text_open_help), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (result.hits.isEmpty()) item("empty") { Text(stringResource(R.string.library_text_empty)) }
                                items(result.hits, key = { "hit:${it.book.id}:${it.generation}:${it.id}" }) { hit ->
                                    Column(Modifier.fillMaxWidth().testTag("library-text-hit-${hit.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(hit.book.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                                        hit.book.authors?.let { Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall) }
                                        if (hit.target.sourceFormat == BookFormat.PDF) {
                                            Text(stringResource(R.string.library_text_pdf_page, requireNotNull(hit.target.pdfPageIndex) + 1),
                                                Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
                                        } else {
                                            Text(stringResource(when (hit.target.sourceFormat) {
                                                BookFormat.TXT -> R.string.library_text_txt_part
                                                BookFormat.FB2 -> R.string.library_text_fb2_part
                                                BookFormat.DOCX -> R.string.library_text_docx_part
                                                else -> R.string.library_text_chapter
                                            },
                                                hit.chapterTitle ?: stringResource(R.string.library_text_chapter_unknown)),
                                                Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
                                            if (hit.target.sourceFormat !in setOf(BookFormat.FB2, BookFormat.DOCX)) Text(stringResource(R.string.library_text_source, hit.target.resourceHref),
                                                Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text(hit.snippet, Modifier.fillMaxWidth())
                                        key(hit, state.revision) {
                                            val actionLease = remember { LibraryTextScreenLease() }
                                            DisposableEffect(actionLease) { onDispose { actionLease.active = false } }
                                            val revision = state.revision
                                            TextButton(onClick = {
                                                if (actionLease.active && mayAct() && live.revision == revision &&
                                                    live.actionsEnabled && live.result?.hits?.singleOrNull {
                                                    it.id == hit.id && it.generation == hit.generation && it.book.id == hit.book.id
                                                } == hit) onOpen(hit)
                                            }, enabled = state.actionsEnabled,
                                                modifier = Modifier.fillMaxWidth().testTag("library-text-open-${hit.id}")) {
                                                Text(stringResource(when (hit.target.sourceFormat) {
                                                    BookFormat.PDF -> R.string.library_text_pdf_open
                                                    BookFormat.TXT -> R.string.library_text_txt_open
                                                    BookFormat.EPUB -> R.string.library_text_open
                                                    BookFormat.CBZ, BookFormat.CBR -> R.string.library_text_cbz_unsupported
                                                    BookFormat.FB2 -> R.string.library_text_fb2_open
                                                    BookFormat.DOCX -> R.string.library_text_docx_open
                                                }),
                                                    Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                            }
                                        }
                                        if (state.action == hit) Text(stringResource(R.string.library_text_checking),
                                            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                        state.feedback?.takeIf { it.hit == hit }?.let { feedback ->
                                            Text(stringResource(if (feedback.kind == LibraryTextActionFeedback.CHANGED)
                                                R.string.library_text_changed else R.string.library_text_open_failed),
                                                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                                        }
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryTextBuildPanel(state: LibraryTextSearchState, mayAct: () -> Boolean,
    currentState: () -> LibraryTextSearchState, onRebuild: (LibraryTextBuildPhase) -> Unit, onCancel: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        when (val build = state.build) {
            LibraryTextBuildPhase.Idle -> Unit
            is LibraryTextBuildPhase.Running -> {
                CircularProgressIndicator()
                if (build.stopping) Text(stringResource(R.string.library_text_stopping), Modifier.fillMaxWidth())
                else {
                    val progress = build.progress
                    if (progress == null) Text(stringResource(R.string.library_text_build_starting), Modifier.fillMaxWidth())
                    else {
                        Text(stringResource(R.string.library_text_build_progress, progress.processed, progress.total),
                            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium)
                        progress.currentBookTitle?.let { Text(stringResource(R.string.library_text_build_book, it),
                            Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium) }
                    }
                    key(build.id) {
                        TextButton(onClick = {
                            val current = currentState().build as? LibraryTextBuildPhase.Running
                            if (mayAct() && current != null && current.id == build.id && !current.stopping) onCancel(build.id)
                        }, enabled = state.active, modifier = Modifier.fillMaxWidth().testTag("library-text-cancel")) {
                            Text(stringResource(R.string.library_text_cancel), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            is LibraryTextBuildPhase.Finished -> Text(stringResource(if (build.cancelled) R.string.library_text_build_cancelled
                else when (build.bookState) {
                    com.yaprak.reader.domain.search.LibraryTextBookState.READY -> R.string.library_text_selected_ready
                    com.yaprak.reader.domain.search.LibraryTextBookState.NO_TEXT -> R.string.library_text_selected_no_text
                    com.yaprak.reader.domain.search.LibraryTextBookState.UNSUPPORTED -> R.string.library_text_selected_unsupported
                    com.yaprak.reader.domain.search.LibraryTextBookState.LIMIT -> R.string.library_text_selected_limit
                    com.yaprak.reader.domain.search.LibraryTextBookState.FAILED,
                    com.yaprak.reader.domain.search.LibraryTextBookState.NOT_INDEXED -> R.string.library_text_selected_failed
                    null -> R.string.library_text_build_finished
                }))
            is LibraryTextBuildPhase.Failed -> {
                Text(stringResource(R.string.library_text_build_failed), color = MaterialTheme.colorScheme.error)
                Text(stringResource(failureText(build.reason)), style = MaterialTheme.typography.bodySmall)
            }
        }
        val expected = state.build
        val expectedScope = state.scope
        val expectedRevision = state.revision
        if (!state.building) key(expected, expectedScope, expectedRevision) {
            TextButton(onClick = {
                val current = currentState()
                if (mayAct() && current.canRebuild && current.build == expected && current.scope == expectedScope &&
                    current.revision == expectedRevision) onRebuild(expected)
            }, enabled = state.canRebuild, modifier = Modifier.fillMaxWidth().testTag("library-text-rebuild")) {
                Text(stringResource(if (state.scope == null) R.string.library_text_rebuild else R.string.library_text_selected_rebuild), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}

private fun failureText(reason: LibraryTextIndexFailure) = when (reason) {
    LibraryTextIndexFailure.INVALID_QUERY -> R.string.library_text_invalid_query
    LibraryTextIndexFailure.RECOVERY -> R.string.library_text_recovery
    LibraryTextIndexFailure.BUSY -> R.string.library_text_busy
    LibraryTextIndexFailure.SOURCE -> R.string.library_text_failed
}
private class LibraryTextScreenLease { var active = true }
