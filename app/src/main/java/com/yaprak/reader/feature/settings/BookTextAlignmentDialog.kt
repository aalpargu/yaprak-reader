package com.yaprak.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yaprak.reader.domain.settings.ReadingTextAlignment

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookTextAlignmentDialog(
    state: BookTextAlignmentState,
    onEdit: (ReadingTextAlignment?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
) {
    if (!state.open) return
    val enabled = !state.saving && !state.loading && !state.loadFailed
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Kitap metin hizalaması", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("Bu kitap için metin hizalamasını seç. Kaydedince uygulanır; genel görünüm ayarları değişmez.")
                when {
                    state.loading -> CircularProgressIndicator()
                    state.loadFailed -> {
                        Text("Kitabın metin hizalaması okunamadı. Mevcut kayıt değiştirilmedi.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onReload) { Text("Yeniden yükle") }
                    }
                    else -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.draft == null,
                                onClick = { onEdit(null) },
                                enabled = enabled,
                                label = { Text("Genel ayarı kullan") },
                            )
                            ReadingTextAlignment.entries.forEach { alignment ->
                                FilterChip(
                                    selected = state.draft == alignment,
                                    onClick = { onEdit(alignment) },
                                    enabled = enabled,
                                    label = {
                                        Text(when (alignment) {
                                            ReadingTextAlignment.PUBLISHER -> "Yayıncı düzeni"
                                            ReadingTextAlignment.START -> "Başlangıca hizala"
                                            ReadingTextAlignment.JUSTIFY -> "İki yana yasla"
                                        })
                                    },
                                )
                            }
                        }
                    }
                }
                if (state.saveFailed) {
                    Text("Kitabın metin hizalaması kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = enabled) { Text(if (state.saving) "Kaydediliyor…" else "Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Vazgeç") } },
    )
}

