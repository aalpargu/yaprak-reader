package com.yaprak.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Recovery when the reset committed but one or more book settings could not be reloaded. */
@Composable
internal fun BookAppearanceResetRefreshDialog(
    open: Boolean,
    retrying: Boolean,
    onRetry: () -> Unit,
    onLeave: () -> Unit,
) {
    if (!open) return
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Görünüm yüklenemedi") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sıfırlama kaydedildi, ancak görünüm yeniden yüklenemedi.")
                Text("Kitabın özel görünüm seçimleri kaldırıldı. Genel ayarların okuyucuya uygulanması tamamlanmadı.")
                Text("Yeniden yüklemeyi dene veya kitaplığa dön.")
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry, enabled = !retrying) {
                Text(if (retrying) "Yeniden yükleniyor…" else "Yeniden yükle")
            }
        },
        dismissButton = { TextButton(onClick = onLeave) { Text("Kitaplığa dön") } },
    )
}
