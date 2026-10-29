package com.yaprak.reader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

@Composable
internal fun ReaderBrightnessDialog(
    open: Boolean,
    draftPercent: Int?,
    onEdit: (Int?) -> Unit,
    onApply: (Int?) -> Unit,
    onDismiss: () -> Unit,
    gestureEnabled: Boolean = false,
    onGestureEnabled: ((Boolean) -> Unit)? = null,
    requestId: String = "legacy-brightness-dialog",
) {
    val liveOpen by rememberUpdatedState(open)
    if (!open) return
    key(requestId) {
    val lease = remember { BrightnessDialogLease() }
    DisposableEffect(lease) { onDispose { lease.active = false } }
    fun fresh() = lease.active && liveOpen
    AlertDialog(
        onDismissRequest = { if (fresh()) onDismiss() },
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Okuma parlaklığı") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Seçim Uygula'dan sonra yalnız bu okuyucu oturumunda geçerli olur. Kitabı yeniden açtığında sistem parlaklığı kullanılır.")
                Column(Modifier.selectableGroup()) {
                    BrightnessChoice("Sistem parlaklığı", draftPercent == null) { if (fresh()) onEdit(null) }
                    BrightnessChoice("Elle ayarla", draftPercent != null) { if (fresh()) onEdit(draftPercent ?: 50) }
                }
                if (draftPercent == null) {
                    Text("Cihazın sistem parlaklığı kullanılır.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Parlaklık: %$draftPercent", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = draftPercent.toFloat(),
                        onValueChange = { if (fresh()) onEdit(it.roundToInt().coerceIn(10, 100)) },
                        valueRange = 10f..100f,
                        steps = 89,
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Okuma parlaklığı yüzdesi"
                            stateDescription = "Yüzde $draftPercent"
                        },
                    )
                    Text("%10–100 arasında seç.", style = MaterialTheme.typography.bodySmall)
                }
                if (onGestureEnabled != null) {
                    Row(Modifier.fillMaxWidth().toggleable(gestureEnabled, enabled = draftPercent != null,
                        role = Role.Switch, onValueChange = { if (fresh() && draftPercent != null) onGestureEnabled(it) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Kaydırarak parlaklık", Modifier.weight(1f).padding(end = 8.dp))
                        Switch(gestureEnabled, onCheckedChange = null, enabled = draftPercent != null)
                    }
                    Text(if (draftPercent == null) "Kaydırmayı açmak için önce Elle ayarla seç."
                        else "Araçlar kapalıyken sol kenarın biraz içinden yukarı veya aşağı kaydır. Parmak kaldırılınca ayar uygulanır; iptalde önceki parlaklık korunur. TalkBack dokunarak keşif açıkken kaydırma kullanılmaz.",
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { if (fresh()) onEdit(null) }) { Text("Sisteme dön") }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (fresh()) onApply(draftPercent) }) { Text("Uygula") }
        },
        dismissButton = { TextButton(onClick = { if (fresh()) onDismiss() }) { Text("Vazgeç") } },
    )
    }
}

private class BrightnessDialogLease { var active = true }

@Composable
private fun BrightnessChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton,
        onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}
