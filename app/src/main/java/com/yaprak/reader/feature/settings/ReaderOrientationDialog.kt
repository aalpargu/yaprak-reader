package com.yaprak.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R
import com.yaprak.reader.domain.settings.ReaderOrientation

@Composable
internal fun ReaderOrientationDialog(state: ReaderOrientationState, onEdit: (ReaderOrientation) -> Unit,
    onSave: () -> Unit, onDismiss: () -> Unit, onRetry: () -> Unit) {
    val live by rememberUpdatedState(state)
    if (!state.open) return
    val editable = !state.loading && !state.loadFailed && !state.saving
    // Material nodes retain callback holders. Recreate them per revision, while the holder
    // outside this key rejects delayed events from every older dialog state.
    key(state.revision) {
        fun fresh() = live == state && live.open
        Dialog(onDismissRequest = { if (fresh() && !live.saving) onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .testTag("reader-orientation-body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.reader_orientation_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.reader_orientation_help), style = MaterialTheme.typography.bodySmall)
                        when {
                            state.loading -> {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.reader_orientation_loading))
                            }
                            state.loadFailed -> {
                                Text(stringResource(R.string.reader_orientation_load_failed), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { if (fresh() && !live.saving && !live.loading) onRetry() },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.reader_orientation_reload), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                            }
                            else -> Column(Modifier.selectableGroup()) {
                                ReaderOrientation.entries.forEach { orientation ->
                                    Row(Modifier.fillMaxWidth().selectable(selected = state.draft == orientation,
                                        enabled = editable, role = Role.RadioButton,
                                        onClick = { if (fresh() && editable) onEdit(orientation) }).padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = state.draft == orientation, onClick = null, enabled = editable)
                                        Text(stringResource(orientation.labelResource()), Modifier.weight(1f).padding(start = 12.dp))
                                    }
                                }
                            }
                        }
                        if (state.saveFailed) Text(stringResource(R.string.reader_orientation_save_failed),
                            color = MaterialTheme.colorScheme.error)
                    }
                    Column(Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { if (fresh() && !live.saving) onDismiss() }, enabled = !state.saving,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.reader_orientation_cancel), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                        TextButton(onClick = { if (fresh() && editable) onSave() }, enabled = editable,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (state.saving) R.string.reader_orientation_saving else R.string.reader_orientation_save),
                                Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

private fun ReaderOrientation.labelResource(): Int = when (this) {
    ReaderOrientation.AUTO -> R.string.reader_orientation_auto
    ReaderOrientation.PORTRAIT -> R.string.reader_orientation_portrait
    ReaderOrientation.LANDSCAPE -> R.string.reader_orientation_landscape
}
