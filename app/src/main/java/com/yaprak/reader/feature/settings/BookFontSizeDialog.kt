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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookFontSizeDialog(
    state: BookFontSizeState,
    onEdit: (Int?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    globalFontSizePercent: Int = 100,
) {
    if (!state.open) return
    val enabled = !state.saving && !state.loading && !state.loadFailed
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kitap yazı boyutu") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bu kitap için yazı boyutunu seç. Kaydedince uygulanır; genel görünüm ayarları değişmez.")
                when {
                    state.loading -> CircularProgressIndicator()
                    state.loadFailed -> {
                        Text("Kitap yazı boyutu okunamadı. Mevcut kayıt değiştirilmedi.", color = MaterialTheme.colorScheme.error)
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
                            FilterChip(
                                selected = state.draft != null,
                                onClick = {
                                    val nearestStep = 75 + ((globalFontSizePercent.coerceIn(75, 200) - 75) / 5f).roundToInt() * 5
                                    onEdit(state.draft ?: state.saved ?: nearestStep)
                                },
                                enabled = enabled,
                                label = { Text("Bu kitap için") },
                            )
                        }
                        val percent = state.draft ?: globalFontSizePercent
                        Text(if (state.draft == null) "Genel yazı boyutu · %$percent" else "Yazı boyutu · %$percent",
                            style = MaterialTheme.typography.titleSmall)
                        Slider(
                            value = percent.toFloat(),
                            onValueChange = { raw ->
                                onEdit((75 + ((raw - 75) / 5).roundToInt() * 5).coerceIn(75..200))
                            },
                            valueRange = 75f..200f,
                            steps = 24,
                            enabled = enabled && state.draft != null,
                            modifier = Modifier.semantics { contentDescription = "Kitap yazı boyutu" },
                        )
                        if (state.draft == null) {
                            Text("Kitaba özel boyut seçmek için Bu kitap için'e dokun.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.saveFailed) {
                    Text("Kitap yazı boyutu kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = enabled) { Text(if (state.saving) "Kaydediliyor…" else "Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Vazgeç") } },
    )
}
