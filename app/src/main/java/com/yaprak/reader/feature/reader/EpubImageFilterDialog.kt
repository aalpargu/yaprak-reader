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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.reader.EpubImageFilterMode

@Composable
internal fun EpubImageFilterDialog(
    open: Boolean,
    draft: EpubImageFilterMode,
    onEdit: (EpubImageFilterMode) -> Unit,
    onApply: (EpubImageFilterMode) -> Unit,
    onDismiss: () -> Unit,
    failed: Boolean = false,
) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Koyu tema görselleri") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Seçim Uygula'dan sonra yalnız bu okuyucu oturumunda geçerli olur. Kitabı yeniden açtığında özgün görseller kullanılır.")
                Text("Filtre yalnız etkin Koyu temada uygulanır. Bu tema genel ayardan, kitaba özel temadan veya koyu Sistem temasından gelebilir.")
                Column(Modifier.selectableGroup()) {
                    EpubImageFilterMode.entries.forEach { mode ->
                        ImageFilterChoice(mode, draft == mode) { onEdit(mode) }
                    }
                }
                Text("Karart ve Renkleri ters çevir, görsel ve diyagram renklerini değiştirir.",
                    style = MaterialTheme.typography.bodySmall)
                if (failed) {
                    Text("Görsel filtresi uygulanamadı. Seçimin korunuyor; Uygula ile yeniden deneyebilir veya vazgeçebilirsin.",
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }) { Text("Uygula") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
private fun ImageFilterChoice(mode: EpubImageFilterMode, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton,
        onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(when (mode) {
            EpubImageFilterMode.ORIGINAL -> "Özgün görseller"
            EpubImageFilterMode.DARKEN -> "Karart"
            EpubImageFilterMode.INVERT -> "Renkleri ters çevir"
        }, Modifier.padding(start = 12.dp))
    }
}
