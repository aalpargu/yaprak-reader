package com.yaprak.reader.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.BookFolderSnapshot

@Composable
internal fun BookFolderPanel(state: BookFolderState, enabled: Boolean, onScan: (BookFolderSnapshot) -> Unit,
    onForget: (BookFolderSnapshot) -> Unit, onRetry: () -> Unit, onCancel: () -> Unit,
    onAutomatic: (BookFolderSnapshot, Boolean) -> Unit = { _, _ -> }) {
    if (!state.available) return
    val live by rememberUpdatedState(state); val liveEnabled by rememberUpdatedState(enabled)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.book_folder_title), style = MaterialTheme.typography.titleMedium)
        when {
            state.busy -> {
                Text(stringResource(if (state.saving) R.string.book_folder_saving else R.string.book_folder_checking),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.resolving) TextButton(onClick = { if (live.resolving) onCancel() }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.book_folder_cancel), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
            state.loading -> Text(stringResource(R.string.book_folder_loading))
            state.failure != null -> {
                Text(stringResource(R.string.book_folder_load_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { if (liveEnabled && !live.busy && live.failure != null) onRetry() },
                    enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.book_folder_retry)) }
            }
            state.snapshot?.folder == null -> Text(stringResource(R.string.book_folder_empty), style = MaterialTheme.typography.bodySmall)
            else -> {
                val snapshot = requireNotNull(state.snapshot)
                Text(stringResource(R.string.book_folder_selected, snapshot.folder?.displayName ?: stringResource(R.string.book_folder_name_unknown)))
                key(snapshot) {
                    var active by remember { mutableStateOf(true) }; DisposableEffect(Unit) { onDispose { active = false } }
                    fun mayAct() = active && liveEnabled && live.ready && !live.busy && live.snapshot == snapshot
                    TextButton(onClick = { if (mayAct()) onAutomatic(snapshot, !snapshot.automaticScan) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("book-folder-automatic")) {
                        Text(stringResource(if (snapshot.automaticScan) R.string.book_folder_auto_turn_off else R.string.book_folder_auto_turn_on),
                            Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                    Text(stringResource(if (snapshot.automaticScan) R.string.book_folder_auto_on else R.string.book_folder_auto_off),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.book_folder_auto_help), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (mayAct()) onScan(snapshot) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("book-folder-scan")) {
                        Text(stringResource(R.string.book_folder_scan), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                    TextButton(onClick = { if (mayAct()) onForget(snapshot) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("book-folder-forget")) {
                        Text(stringResource(R.string.book_folder_forget), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
                Text(stringResource(R.string.book_folder_help), style = MaterialTheme.typography.bodySmall)
            }
        }
        state.feedback?.takeIf { it.sessionId == null }?.let { BookFolderFeedbackText(it) }
    }
}

@Composable
internal fun BookFolderFeedbackText(feedback: BookFolderFeedback) {
    Text(stringResource(when (feedback.kind) {
        BookFolderFeedback.Kind.SAVED -> R.string.book_folder_saved
        BookFolderFeedback.Kind.FORGOTTEN -> R.string.book_folder_forgotten
        BookFolderFeedback.Kind.AUTO_ENABLED -> R.string.book_folder_auto_enabled
        BookFolderFeedback.Kind.AUTO_DISABLED -> R.string.book_folder_auto_disabled
        BookFolderFeedback.Kind.CLEANUP -> R.string.book_folder_cleanup
        BookFolderFeedback.Kind.FAILED -> R.string.book_folder_failed
        BookFolderFeedback.Kind.CHANGED -> R.string.book_folder_changed
        BookFolderFeedback.Kind.ACCESS -> R.string.book_folder_access
    }), Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
}
