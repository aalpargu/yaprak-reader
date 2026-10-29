package com.yaprak.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun BookAppearanceResetDialog(
    state: BookAppearanceResetState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kitap görünümünü sıfırla") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bu kitabın tema, yazı boyutu, yazı ailesi, kenar boşluğu, satır, paragraf ve kelime aralığı ile metin hizalaması ve okuma modu için kayıtlı özel seçimleri kaldırılacak.")
                Text("Bu dokuz alan güncel genel görünüm ayarlarını kullanacak.")
                Text("Genel görünüm ayarları, okuma konumu ve yer imleri değişmeyecek.")
                if (state.saveFailed) {
                    Text(
                        "Kitap görünümü sıfırlanamadı. Tekrar deneyebilir veya vazgeçebilirsin.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !state.saving) {
                Text(when {
                    state.saving -> "Sıfırlanıyor…"
                    state.saveFailed -> "Tekrar dene"
                    else -> "Sıfırla"
                })
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Vazgeç") }
        },
    )
}
