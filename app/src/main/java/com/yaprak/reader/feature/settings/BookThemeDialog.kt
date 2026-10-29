package com.yaprak.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yaprak.reader.domain.settings.ReadingTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookThemeDialog(
    state: BookThemeState,
    onEdit: (ReadingTheme?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
) {
    if (!state.open) return
    val enabled = !state.saving && !state.loading && !state.loadFailed
    val density = LocalDensity.current
    val themeChoiceModifier = if (density.fontScale >= 1.5f) Modifier.fillMaxWidth().heightIn(
        min = with(density) { MaterialTheme.typography.labelLarge.lineHeight.toDp() } + 16.dp,
    ) else Modifier
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kitap teması") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bu kitap için tema seç. Kaydedince uygulanır; genel metin görünüm ayarları değişmez.")
                when {
                    state.loading -> CircularProgressIndicator()
                    state.loadFailed -> {
                        Text("Kitap teması okunamadı. Mevcut kayıt değiştirilmedi.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onReload) { Text("Yeniden yükle") }
                    }
                    else -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.draft == null,
                                onClick = { onEdit(null) },
                                enabled = enabled,
                                modifier = themeChoiceModifier,
                                label = { Text("Genel ayarı kullan") },
                            )
                            ReadingTheme.entries.forEach { theme ->
                                FilterChip(
                                    selected = state.draft == theme,
                                    onClick = { onEdit(theme) },
                                    enabled = enabled,
                                    modifier = themeChoiceModifier,
                                    label = {
                                        Text(when (theme) {
                                            ReadingTheme.SYSTEM -> "Sistem"
                                            ReadingTheme.LIGHT -> "Açık"
                                            ReadingTheme.SEPIA -> "Sepya"
                                            ReadingTheme.DARK -> "Koyu"
                                            ReadingTheme.AMOLED -> "AMOLED siyah"
                                        })
                                    },
                                )
                            }
                        }
                    }
                }
                if (state.saveFailed) {
                    Text("Kitap teması kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = enabled) { Text(if (state.saving) "Kaydediliyor…" else "Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Vazgeç") } },
    )
}
