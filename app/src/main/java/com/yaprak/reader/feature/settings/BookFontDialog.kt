package com.yaprak.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.yaprak.reader.domain.settings.ReadingFont

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookFontDialog(
    state: BookFontState,
    onEdit: (ReadingFont?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
) {
    if (!state.open) return
    val enabled = !state.saving && !state.loading && !state.loadFailed
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kitap yazı ailesi") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bu kitap için yazı ailesini seç. Kaydedince uygulanır; genel görünüm ayarları değişmez.")
                when {
                    state.loading -> CircularProgressIndicator()
                    state.loadFailed -> {
                        Text("Kitap yazı ailesi okunamadı. Mevcut kayıt değiştirilmedi.", color = MaterialTheme.colorScheme.error)
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
                            ReadingFont.entries.forEach { font ->
                                FilterChip(
                                    selected = state.draft == font,
                                    onClick = { onEdit(font) },
                                    enabled = enabled,
                                    label = {
                                        Text(when (font) {
                                            ReadingFont.ORIGINAL -> "Kitabın yazısı"
                                            ReadingFont.SERIF -> "Serif"
                                            ReadingFont.SANS_SERIF -> "Sans serif"
                                        })
                                    },
                                )
                            }
                        }
                    }
                }
                if (state.saveFailed) {
                    Text("Kitap yazı ailesi kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = enabled) { Text(if (state.saving) "Kaydediliyor…" else "Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Vazgeç") } },
    )
}

