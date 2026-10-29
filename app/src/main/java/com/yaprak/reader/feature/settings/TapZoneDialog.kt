package com.yaprak.reader.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.domain.settings.*

@Composable
internal fun TapZoneDialog(state: TapZoneState, onEdit: (TapZoneSettings) -> Unit,
    onSave: () -> Unit, onDismiss: () -> Unit, onRetry: () -> Unit) {
    if (!state.open) return
    val enabled = !state.loading && !state.loadFailed && !state.saving
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Dokunma bölgeleri") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Okuma alanı soldan sağa üç eşit bölgedir. EPUB, PDF, TXT ve CBZ/CBR için bu cihazda hatırlanır. Kapalıyken mevcut dokunma davranışı kullanılır. Bağlantılar, uzun basarak seçim, kaydırma ve PDF'de çift dokunarak yakınlaştırma korunur.")
                when {
                    state.loading -> { CircularProgressIndicator(); Text("Ayar okunuyor…") }
                    state.loadFailed -> {
                        Text("Dokunma bölgeleri okunamadı.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry, enabled = !state.saving) { Text("Yeniden yükle") }
                    }
                    else -> {
                        Row(Modifier.fillMaxWidth().toggleable(value = state.draft.enabled,
                            enabled = enabled, role = Role.Switch,
                            onValueChange = { onEdit(state.draft.copy(enabled = it)) }).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Özel dokunma bölgeleri", Modifier.weight(1f).padding(end = 12.dp))
                            Switch(checked = state.draft.enabled, onCheckedChange = null, enabled = enabled)
                        }
                        Text("Araçlar açıkken dokunma yalnızca araçları gizler. Önceki ve sonraki kitap sırasına göre ilerler; bölge yerleri ekran döndüğünde de sol / orta / sağ kalır.")
                        TapZone.entries.forEach { zone ->
                            val name = when (zone) {
                                TapZone.LEFT -> "Sol"
                                TapZone.MIDDLE -> "Orta"
                                TapZone.RIGHT -> "Sağ"
                            }
                            Text(name + " bölge", style = MaterialTheme.typography.titleMedium)
                            TapZoneAction.entries.forEach { action ->
                                val label = when (action) {
                                    TapZoneAction.PREVIOUS -> "Önceki sayfa"
                                    TapZoneAction.MENU -> "Araçları göster"
                                    TapZoneAction.NEXT -> "Sonraki sayfa"
                                    TapZoneAction.NONE -> "İşlem yapma"
                                }
                                Row(Modifier.fillMaxWidth().selectable(
                                    selected = state.draft.action(zone) == action, enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = { onEdit(state.draft.withAction(zone, action)) })
                                    .semantics { contentDescription = name + " bölge: " + label }
                                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = state.draft.action(zone) == action,
                                        onClick = null, enabled = enabled)
                                    Text(label, Modifier.weight(1f).padding(start = 8.dp))
                                }
                            }
                        }
                        if (!state.draft.valid)
                            Text("En az bir bölge araçları göstermeli.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { onEdit(TapZoneSettings(enabled = state.draft.enabled)) },
                            enabled = enabled) { Text("Bölge atamalarını sıfırla") }
                    }
                }
                if (state.saveFailed) Text("Dokunma bölgeleri kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.",
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = enabled && state.draft.valid) {
            Text(if (state.saving) "Kaydediliyor…" else "Kaydet")
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Vazgeç") } },
    )
}
