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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yaprak.reader.domain.settings.BookLineHeightChoice
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookLineHeightDialog(
    state: BookLineHeightState,
    onEdit: (BookLineHeightChoice) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    globalLineHeightPercent: Int? = null,
) {
    if (!state.open) return
    val enabled = !state.saving && !state.loading && !state.loadFailed
    // Reseed after the asynchronous saved choice arrives, while retaining local edits as chips change.
    var customPercent by remember(state.saved, state.loading) {
        mutableIntStateOf(
            when (val choice = state.draft) {
                is BookLineHeightChoice.Custom -> choice.percent
                else -> (state.saved as? BookLineHeightChoice.Custom)?.percent
                    ?: globalLineHeightPercent?.let(::snapLineHeight)
                    ?: 140
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kitap satır aralığı") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bu kitap için satır aralığını seç. Kaydedince uygulanır; genel görünüm ayarları değişmez.")
                when {
                    state.loading -> CircularProgressIndicator()
                    state.loadFailed -> {
                        Text("Kitap satır aralığı okunamadı. Mevcut kayıt değiştirilmedi.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onReload) { Text("Yeniden yükle") }
                    }
                    else -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.draft == BookLineHeightChoice.Inherit,
                                onClick = { onEdit(BookLineHeightChoice.Inherit) },
                                enabled = enabled,
                                label = { Text("Genel ayarı kullan") },
                            )
                            FilterChip(
                                selected = state.draft == BookLineHeightChoice.Publisher,
                                onClick = { onEdit(BookLineHeightChoice.Publisher) },
                                enabled = enabled,
                                label = { Text("Yayıncı düzeni") },
                            )
                            FilterChip(
                                selected = state.draft is BookLineHeightChoice.Custom,
                                onClick = { onEdit(BookLineHeightChoice.Custom(customPercent)) },
                                enabled = enabled,
                                label = { Text("Bu kitap için yüzde") },
                            )
                        }
                        val label = when (val choice = state.draft) {
                            BookLineHeightChoice.Inherit -> globalLineHeightPercent?.let { "Genel satır aralığı · %$it" }
                                ?: "Genel satır aralığı · Yayıncı düzeni"
                            BookLineHeightChoice.Publisher -> "Kitap satır aralığı · Yayıncı düzeni"
                            is BookLineHeightChoice.Custom -> "Kitap satır aralığı · %${choice.percent}"
                        }
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        val sliderPercent = (state.draft as? BookLineHeightChoice.Custom)?.percent ?: customPercent
                        Slider(
                            value = sliderPercent.toFloat(),
                            onValueChange = { raw ->
                                customPercent = snapLineHeight(raw.roundToInt())
                                onEdit(BookLineHeightChoice.Custom(customPercent))
                            },
                            valueRange = 100f..220f,
                            steps = 11,
                            enabled = enabled && state.draft is BookLineHeightChoice.Custom,
                            modifier = Modifier.semantics { contentDescription = "Kitap satır aralığı" },
                        )
                        if (state.draft !is BookLineHeightChoice.Custom) {
                            Text("Kitaba özel yüzde seçmek için Bu kitap için yüzde'ye dokun.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Özel satır aralığı yayıncının bazı paragraf stillerini değiştirir.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.saveFailed) {
                    Text("Kitap satır aralığı kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = enabled) { Text(if (state.saving) "Kaydediliyor…" else "Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Vazgeç") } },
    )
}

private fun snapLineHeight(value: Int): Int =
    (100 + ((value.coerceIn(100, 220) - 100) / 10f).roundToInt() * 10).coerceIn(100..220)
