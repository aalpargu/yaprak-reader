package com.yaprak.reader.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R

@Composable
internal fun LibraryBackupPanel(
    createEnabled: Boolean,
    restoreEnabled: Boolean,
    running: BackupOperation?,
    pickerPending: Boolean,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
) {
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium)
            }
            if (expanded || running != null || pickerPending) {
                Text(stringResource(R.string.backup_description), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.backup_unencrypted), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onCreate, enabled = createEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_create))
                }
                OutlinedButton(onClick = { confirmRestore = true }, enabled = restoreEnabled,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_restore))
                }
                Text(stringResource(R.string.backup_restore_empty_only), style = MaterialTheme.typography.bodySmall)
                if (running != null || pickerPending) {
                    Text(stringResource(when (running) {
                        BackupOperation.CREATE -> R.string.backup_creating
                        BackupOperation.RESTORE -> R.string.backup_restoring
                        null -> R.string.backup_selecting
                    }), style = MaterialTheme.typography.bodyMedium)
                    if (running != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
        text = { Text(stringResource(R.string.backup_restore_confirm_body),
            modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = { confirmRestore = false; onRestore() }, enabled = restoreEnabled) {
                Text(stringResource(R.string.backup_choose_archive))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.cancel_remove)) }
        },
    )
}
