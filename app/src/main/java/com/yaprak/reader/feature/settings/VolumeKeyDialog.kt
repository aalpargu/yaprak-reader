package com.yaprak.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun VolumeKeyDialog(
    state: VolumeKeyState,
    onEdit: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    if (!state.open) return
    val enabled = !state.loading && !state.loadFailed && !state.saving
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Okuma kontrolleri") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Varsayılan olarak kapalıdır. EPUB, PDF, TXT ve CBZ/CBR okurken ses açma tuşu önceki, ses kısma tuşu sonraki sayfaya geçer. Her basışta bir kez çevirir. Menü veya metin seçimi açıkken tuşlar normal ses kontrolünü sürdürür. Kaydedilen tercih bu cihazda sonraki açılışlarda da kullanılır.")
                when {
                    state.loading -> {
                        CircularProgressIndicator()
                        Text("Ayar okunuyor…")
                    }
                    state.loadFailed -> {
                        Text("Okuma kontrolleri okunamadı. Yeniden yükleyip tekrar dene.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry, enabled = !state.loading && !state.saving) {
                            Text("Yeniden yükle")
                        }
                    }
                    else -> Row(
                        modifier = Modifier.fillMaxWidth()
                            .toggleable(value = state.draft, enabled = enabled, role = Role.Switch, onValueChange = onEdit)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Ses tuşlarıyla sayfa çevir", Modifier.weight(1f).padding(end = 12.dp))
                        Switch(checked = state.draft, onCheckedChange = null, enabled = enabled)
                    }
                }
                if (state.saveFailed) {
                    Text("Okuma kontrolleri kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = enabled) {
                Text(if (state.saving) "Kaydediliyor…" else "Kaydet")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Vazgeç") }
        },
    )
}
