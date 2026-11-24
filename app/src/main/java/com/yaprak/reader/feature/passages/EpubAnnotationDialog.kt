package com.yaprak.reader.feature.passages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yaprak.reader.R
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.passages.EpubPassageRepository
import com.yaprak.reader.domain.passages.MAX_EPUB_ATTACHED_NOTE_LENGTH

@Composable
internal fun EpubAnnotationRoute(request: EpubAnnotationRequest, repository: EpubPassageRepository,
    canMutate: () -> Boolean, onDismiss: () -> Unit) {
    val model: EpubAnnotationViewModel = viewModel(key = "annotation-${request.sessionId}", factory = viewModelFactory {
        initializer { EpubAnnotationViewModel(request, repository) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val liveAllowed by rememberUpdatedState(canMutate)
    val liveDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(model) { onDispose { model.close() } }
    LaunchedEffect(model, state.completed) {
        if (state.completed && model.state.value.completed && !model.state.value.closed) liveDismiss()
    }
    EpubAnnotationDialog(request.sessionId, request.text, state.color, state.note, state.busy,
        state.canSave && canMutate(), state.revision, failed = state.failed,
        onColor = { if (liveAllowed()) model.editColor(it) },
        onNote = { if (liveAllowed()) model.editNote(it) },
        onSave = { if (liveAllowed()) model.save() },
        onDismiss = { if (!model.state.value.busy) { model.close(); liveDismiss() } })
}

/** Fixed final actions and a bounded scrolling body; no selection or book text is altered. */
@Composable
internal fun EpubAnnotationDialog(sessionId: String, text: String, color: EpubHighlightColor?, note: String,
    busy: Boolean, canSave: Boolean, revision: Long, failed: Boolean = false, changed: Boolean = false,
    editing: Boolean = false, onColor: (EpubHighlightColor?) -> Unit, onNote: (String) -> Unit,
    onSave: () -> Unit, onDismiss: () -> Unit, readerUnavailable: Boolean = false,
    recoveryContent: (@Composable () -> Unit)? = null) {
    val signature = listOf(sessionId, text, color, note, busy, canSave, revision, failed, changed, editing, readerUnavailable)
    val live by rememberUpdatedState(signature)
    // Keep the text field and focus alive while typing. Only action holders are replaced per
    // rendered revision, so a captured Material click cannot retarget a newer draft.
    key(sessionId) {
        fun fresh() = live == signature
        Dialog(onDismissRequest = { if (fresh() && !busy) onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .testTag("epub-annotation-body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(if (editing) R.string.annotation_edit_title else R.string.annotation_create_title),
                            style = MaterialTheme.typography.titleLarge)
                        Text(text, Modifier.testTag("epub-annotation-selection"), maxLines = 4,
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif),
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        recoveryContent?.invoke()
                        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            (listOf<EpubHighlightColor?>(null) + EpubHighlightColor.entries).forEach { option -> key(signature, option) {
                                val label = option.colorLabel()
                                val selected = color == option
                                Box(Modifier.size(44.dp).clip(CircleShape)
                                    .background(option?.swatch() ?: MaterialTheme.colorScheme.surface)
                                    .border(if (selected) 3.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                                    .selectable(selected = selected, role = Role.RadioButton, enabled = !busy && !changed,
                                        onClick = { if (fresh() && !busy && !changed) onColor(option) })
                                    .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                                    if (option == null) Text("∅", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    else if (selected) Text("✓", color = Color(0xFF1B1B1B))
                                }
                            } }
                        }
                        OutlinedTextField(note, onValueChange = { if (fresh() && !busy && !changed) onNote(it) },
                            modifier = Modifier.fillMaxWidth().testTag("epub-annotation-note"), enabled = !busy && !changed,
                            label = { Text(stringResource(R.string.annotation_note_label)) }, minLines = 2, maxLines = 5,
                            isError = !validAttachedNoteDraft(note), supportingText = {
                                Text(stringResource(R.string.annotation_note_limit, note.length, MAX_EPUB_ATTACHED_NOTE_LENGTH))
                            })
                        if (note.isNotEmpty()) key(signature) { TextButton(onClick = { if (fresh() && !busy && !changed) onNote("") },
                            enabled = !busy && !changed, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.annotation_clear_note), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        } }
                        if (readerUnavailable) Text(readerUnavailableMessage, color = MaterialTheme.colorScheme.error)
                        if (changed) Text(stringResource(R.string.annotation_changed), color = MaterialTheme.colorScheme.error)
                        if (failed) Text(stringResource(R.string.annotation_save_failed), color = MaterialTheme.colorScheme.error)
                    }
                    // Two equal buttons; at large font scales labels wrap inside their half instead of overflowing.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        key(signature, "cancel") { androidx.compose.material3.OutlinedButton(onClick = { if (fresh() && !busy) onDismiss() }, enabled = !busy,
                            modifier = Modifier.weight(1f).testTag("epub-annotation-cancel")) {
                            Text(stringResource(R.string.annotation_cancel), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        } }
                        key(signature, "save") { androidx.compose.material3.Button(onClick = { if (fresh() && canSave && !busy && !changed) onSave() },
                            enabled = canSave && !busy && !changed, modifier = Modifier.weight(1f).testTag("epub-annotation-save")) {
                            Text(stringResource(if (busy) R.string.annotation_saving else R.string.annotation_save), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        } }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EpubHighlightColor?.colorLabel(): String = stringResource(when (this) {
    null -> R.string.annotation_no_color
    EpubHighlightColor.YELLOW -> R.string.annotation_yellow
    EpubHighlightColor.GREEN -> R.string.annotation_green
    EpubHighlightColor.BLUE -> R.string.annotation_blue
    EpubHighlightColor.PINK -> R.string.annotation_pink
})

internal const val readerUnavailableMessage = "Okuyucu henüz hazır değil. Bu pencereyi kapatıp hata bildiriminden yeniden deneyebilirsin."

internal fun EpubHighlightColor.swatch(): Color = when (this) {
    EpubHighlightColor.YELLOW -> Color(0xFFFFE082)
    EpubHighlightColor.GREEN -> Color(0xFFA5D6A7)
    EpubHighlightColor.BLUE -> Color(0xFF90CAF9)
    EpubHighlightColor.PINK -> Color(0xFFF48FB1)
}
