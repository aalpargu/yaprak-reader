package com.yaprak.reader.feature.library

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.BookCandidate
import com.yaprak.reader.domain.library.BookFolderSnapshot
import com.yaprak.reader.domain.library.DiscoveryFailure
import com.yaprak.reader.domain.library.DiscoveryLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun BookDiscoveryDialog(
    state: DiscoveryState,
    onSearch: (String, String) -> Unit,
    onSelect: (String, String, Boolean) -> Unit,
    onSelectAll: (String) -> Unit,
    onClearSelection: (String) -> Unit,
    onImport: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onCancel: (String) -> Unit,
    folderState: BookFolderState = BookFolderState(),
    onRememberFolder: ((String, String, BookFolderSnapshot) -> Unit)? = null,
) {
    val id = state.sessionIdOrNull()
    val liveId by rememberUpdatedState(id)
    val liveFolder by rememberUpdatedState(folderState)
    if (id == null || state is DiscoveryState.Choosing) return
    // Material retains callbacks inside button semantics. Give every scan its own holders,
    // while keeping the live session outside the keyed group to reject disposed actions.
    key(id) {
        var active by remember { mutableStateOf(true) }
        DisposableEffect(Unit) { onDispose { active = false } }
        val current = { active && liveId == id && !liveFolder.busy }
        val preview = state as? DiscoveryState.Preview
        val matching by produceState<List<BookCandidate>>(emptyList(), preview?.candidates, preview?.query) {
            value = withContext(Dispatchers.Default) {
                preview?.let { matchingDiscoveryCandidates(it.candidates, it.query) }.orEmpty()
            }
        }
        Dialog(onDismissRequest = { if (current()) onDismiss(id) },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.headlineSmall) }
                    when (state) {
                        is DiscoveryState.Scanning -> {
                            item { Text(stringResource(R.string.discovery_scanning), style = MaterialTheme.typography.titleMedium) }
                            item { Text(stringResource(R.string.discovery_progress, state.progress.visited, state.progress.candidates),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            if (state.cancelRequested) item { Text(stringResource(R.string.discovery_stopping)) }
                            item { TextButton(onClick = { if (current()) onCancel(id) }, enabled = !state.cancelRequested) {
                                Text(stringResource(R.string.discovery_stop))
                            } }
                        }
                        is DiscoveryState.Failed -> {
                            item { Text(stringResource(state.reason.messageResource()),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                color = MaterialTheme.colorScheme.error) }
                            item { TextButton(onClick = { if (current()) onDismiss(id) }) { Text(stringResource(R.string.close)) } }
                        }
                        is DiscoveryState.Preview -> {
                            if (folderState.available && state.treeUri != null && onRememberFolder != null) item("remember-folder") {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.book_folder_remember_help), style = MaterialTheme.typography.bodySmall)
                                    val expected = folderState.snapshot
                                    TextButton(onClick = {
                                        if (current() && liveFolder.ready && expected != null && liveFolder.snapshot == expected)
                                            onRememberFolder(id, state.treeUri, expected)
                                    }, enabled = folderState.ready && !folderState.busy,
                                        modifier = Modifier.fillMaxWidth().testTag("book-folder-remember")) {
                                        Text(stringResource(R.string.book_folder_remember), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                    }
                                    if (folderState.saving) Text(stringResource(R.string.book_folder_saving))
                                    if (folderState.loading) Text(stringResource(R.string.book_folder_loading))
                                    if (folderState.failure != null) Text(stringResource(R.string.book_folder_load_failed), color = MaterialTheme.colorScheme.error)
                                    folderState.feedback?.takeIf { it.sessionId == id }?.let { BookFolderFeedbackText(it) }
                                }
                            }
                            item { Text(stringResource(R.string.discovery_found_count, state.candidates.size, state.visited)) }
                            state.limit?.let { limit -> item { Text(stringResource(limit.messageResource()),
                                color = MaterialTheme.colorScheme.error) } }
                            item { Text(stringResource(R.string.discovery_preview_help), style = MaterialTheme.typography.bodySmall) }
                            item { OutlinedTextField(state.query, onValueChange = { if (current()) onSearch(id, it) },
                                singleLine = true, enabled = !folderState.busy, modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.discovery_search)) }) }
                            item { Text(stringResource(R.string.discovery_selected_count, state.selectedIds.size, MAX_IMPORT_SELECTION),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                            item { TextButton(onClick = { if (current()) onSelectAll(id) }, enabled = !folderState.busy && matching.isNotEmpty()) {
                                Text(stringResource(R.string.discovery_select_all, MAX_IMPORT_SELECTION))
                            } }
                            item { TextButton(onClick = { if (current()) onClearSelection(id) }, enabled = !folderState.busy && state.selectedIds.isNotEmpty()) {
                                Text(stringResource(R.string.discovery_clear_selection))
                            } }
                            item { Text(stringResource(R.string.discovery_selection_help, MAX_IMPORT_SELECTION),
                                style = MaterialTheme.typography.bodySmall) }
                            if (state.candidates.isEmpty()) item { Text(stringResource(R.string.discovery_empty)) }
                            else if (matching.isEmpty()) item { Text(stringResource(R.string.discovery_no_matches)) }
                            items(matching, key = BookCandidate::id) { candidate ->
                                val selected = candidate.id in state.selectedIds
                                val enabled = !folderState.busy && (selected || state.selectedIds.size < MAX_IMPORT_SELECTION)
                                Row(Modifier.fillMaxWidth().toggleable(selected, enabled = enabled, role = Role.Checkbox,
                                    onValueChange = { value -> if (current()) onSelect(id, candidate.id, value) })
                                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(selected, onCheckedChange = null, enabled = enabled)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(candidate.name)
                                        val size = candidate.sizeBytes?.takeIf { it >= 0 }?.let {
                                            Formatter.formatFileSize(LocalContext.current, it)
                                        } ?: stringResource(R.string.discovery_unknown_size)
                                        Text("${candidate.formatHint.name} · $size", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        else -> Unit
                    }
                }
                if (preview != null) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { if (current()) onDismiss(id) }, enabled = !folderState.busy,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cancel_remove), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                    TextButton(onClick = { if (current()) onImport(id) }, enabled = !folderState.busy && preview.selectedIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.discovery_import_selected, preview.selectedIds.size), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
                }
            }
        }
    }
}

private fun DiscoveryFailure.messageResource(): Int = when (this) {
    DiscoveryFailure.INVALID_TREE -> R.string.discovery_invalid_tree
    DiscoveryFailure.ACCESS -> R.string.discovery_access
    DiscoveryFailure.IO -> R.string.discovery_io
    DiscoveryFailure.UNEXPECTED -> R.string.discovery_unexpected
}

private fun DiscoveryLimit.messageResource(): Int = when (this) {
    DiscoveryLimit.DEPTH -> R.string.discovery_limit_depth
    DiscoveryLimit.DOCUMENTS -> R.string.discovery_limit_documents
    DiscoveryLimit.CANDIDATES -> R.string.discovery_limit_candidates
    DiscoveryLimit.TIME -> R.string.discovery_limit_time
}
