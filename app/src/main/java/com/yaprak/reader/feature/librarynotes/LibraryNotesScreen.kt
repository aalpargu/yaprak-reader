package com.yaprak.reader.feature.librarynotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.notes.LibraryNote
import com.yaprak.reader.domain.notes.LibraryNoteSource
import com.yaprak.reader.domain.notes.MAX_LIBRARY_NOTE_RESULTS

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun LibraryNotesScreen(
    state: LibraryNotesState,
    onSearch: (String) -> Unit,
    onReload: () -> Unit,
    onCopy: (LibraryNote) -> Unit,
    onOpen: (LibraryNote) -> Unit,
    onBack: () -> Unit,
    onExport: ((LibraryNotesState) -> Unit)? = null,
    exportBusy: Boolean = false,
) {
    // These holders must outlive key(note): old Material callbacks keep their captured revision.
    val liveState by rememberUpdatedState(state)
    Scaffold(bottomBar = { LibraryNotesFooter(onBack) }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 900.dp).fillMaxSize().testTag("library-notes-list"),
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "title") { Text(stringResource(R.string.library_notes_title), style = MaterialTheme.typography.headlineMedium) }
                item(key = "help") { Text(stringResource(R.string.library_notes_help),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item(key = "query") {
                    Column {
                        OutlinedTextField(value = state.query, onValueChange = onSearch,
                            modifier = Modifier.fillMaxWidth().testTag("library-notes-search"),
                            label = { Text(stringResource(R.string.library_notes_search)) }, singleLine = true)
                        if (state.query.isNotEmpty()) TextButton(onClick = { onSearch("") }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.clear_search), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                }
                if (onExport != null) item(key = "export") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { onExport(state) }, enabled = state.actionsEnabled && !exportBusy && state.totalCount > 0,
                            modifier = Modifier.fillMaxWidth().testTag("library-notes-export")) {
                            Text(stringResource(R.string.library_notes_export_action), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                        if (exportBusy) Text(stringResource(R.string.library_notes_export_busy),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                when {
                    state.loading -> item(key = "loading") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.library_notes_loading), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                    state.loadFailed -> item(key = "failed") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.library_notes_load_failed), color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            TextButton(onClick = onReload, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.retry), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            }
                        }
                    }
                    else -> {
                        item(key = "count") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.library_notes_count, state.totalCount))
                                if (state.totalCount > state.entries.size)
                                    Text(stringResource(R.string.library_notes_limit, MAX_LIBRARY_NOTE_RESULTS, state.totalCount),
                                        style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = onReload, modifier = Modifier.fillMaxWidth().testTag("library-notes-reload")) {
                                    Text(stringResource(R.string.library_notes_reload), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                            }
                        }
                        if (state.entries.isEmpty()) item(key = "empty") {
                            Text(stringResource(if (state.query.isBlank()) R.string.library_notes_empty else R.string.library_notes_no_matches))
                        }
                        items(state.entries, key = { "${it.source.name}:${it.id}" }) { note ->
                            val rowId = when (note.source) {
                                LibraryNoteSource.BOOK_NOTE -> note.id
                                LibraryNoteSource.EPUB_PASSAGE -> "epub-passage-${note.id}"
                                LibraryNoteSource.PDF_ANNOTATION -> "pdf-annotation-${note.id}"
                            }
                            Column(Modifier.fillMaxWidth().testTag("library-note-$rowId"),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(note.bookTitle, style = MaterialTheme.typography.titleMedium)
                                note.bookAuthors?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(when {
                                    note.source == LibraryNoteSource.EPUB_PASSAGE -> stringResource(R.string.library_notes_attached_source)
                                    note.bookFormat == BookFormat.PDF && note.pdfPageIndex != null ->
                                        stringResource(R.string.library_notes_pdf_page, note.pdfPageIndex.toLong() + 1)
                                    else -> stringResource(R.string.library_notes_book_note, note.bookFormat.name)
                                },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                key(note) {
                                    val callbackAllowed = {
                                        liveState.actionsEnabled && liveState.entries.singleOrNull { it.identity == note.identity } == note
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { if (callbackAllowed()) onCopy(note) }, enabled = state.actionsEnabled,
                                            modifier = Modifier.fillMaxWidth().testTag("library-note-copy-$rowId").semantics {
                                                contentDescription = "${note.bookTitle}, ${note.text.replace('\n', ' ').take(40)}, kopyala"
                                            }) { Text(stringResource(R.string.library_notes_copy),
                                                Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                                        TextButton(onClick = { if (callbackAllowed()) onOpen(note) }, enabled = state.actionsEnabled,
                                            modifier = Modifier.fillMaxWidth().testTag("library-note-open-$rowId").semantics {
                                                contentDescription = "${note.bookTitle}, kitabı aç"
                                            }) { Text(stringResource(R.string.library_notes_open),
                                                Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                                    }
                                }
                                state.action?.takeIf { it == note }?.let {
                                    Text(stringResource(R.string.library_notes_checking),
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                }
                                Text(note.text)
                                note.sourceText?.let { quote ->
                                    Text(stringResource(R.string.library_notes_source_quote), style = MaterialTheme.typography.labelMedium)
                                    Text(quote, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                }
                                state.feedback?.takeIf { it.note == note }?.let { feedback ->
                                    val resource = when (feedback.kind) {
                                        LibraryNoteFeedbackKind.COPIED -> R.string.library_notes_copied
                                        LibraryNoteFeedbackKind.COPY_FAILED -> R.string.library_notes_copy_failed
                                        LibraryNoteFeedbackKind.OPEN_FAILED -> R.string.library_notes_open_failed
                                        LibraryNoteFeedbackKind.CHANGED -> R.string.library_notes_changed
                                    }
                                    Text(stringResource(resource),
                                        color = if (feedback.kind == LibraryNoteFeedbackKind.COPIED) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
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

@Composable
internal fun LibraryNotesFooter(onBack: () -> Unit, navigationInsets: WindowInsets = WindowInsets.navigationBars) {
    Box(Modifier.fillMaxWidth().windowInsetsPadding(navigationInsets).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center) {
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("library-notes-back")) {
            Text(stringResource(R.string.library_notes_back), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}
