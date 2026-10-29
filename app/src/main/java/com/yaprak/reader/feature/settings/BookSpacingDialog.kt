package com.yaprak.reader.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.domain.settings.*
import kotlin.math.roundToInt

@Composable
internal fun BookSpacingDialog(
    state: BookSpacingState, onEdit: (BookSpacingSettings) -> Unit, onSave: () -> Unit,
    onDismiss: () -> Unit, onReload: () -> Unit, global: EpubSettings,
) {
    if (!state.open) return
    val enabled = !state.loading && !state.loadFailed && !state.saving
    AlertDialog(onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Kitap metin aralıkları") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Genel ayarı kullan seçimi, genel ayar değiştiğinde onu izler. Yayıncı seçimi kitabın özgün aralığını kullanır. Kaydet ile yalnız bu kitap değişir.")
                when {
                    state.loading -> CircularProgressIndicator()
                    state.loadFailed -> {
                        Text("Kitap aralıkları okunamadı. Mevcut kayıt değiştirilmedi.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onReload) { Text("Yeniden yükle") }
                    }
                    else -> {
                        SpacingChoiceSection("Paragraf aralığı", "Paragraf boşluğu", state.draft.paragraphs,
                            global.paragraphSpacingPercent, 0..200, 100, enabled) { onEdit(state.draft.copy(paragraphs = it)) }
                        SpacingChoiceSection("Kelime aralığı", "Ek kelime boşluğu", state.draft.words,
                            global.wordSpacingPercent, 0..100, 20, enabled) { onEdit(state.draft.copy(words = it)) }
                        Text("Boşluk miktarı yazı boyutuna göredir; kelime boşluğu mevcut aralığa eklenir. Özel aralıklar yalnız desteklenen metin düzenlerinde uygulanır.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.saveFailed) Text("Kitap aralıkları kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.",
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = enabled) { Text(if (state.saving) "Kaydediliyor…" else "Kaydet") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Vazgeç") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpacingChoiceSection(
    label: String, sliderLabel: String, choice: BookSpacingChoice, global: Int?, range: IntRange,
    fallback: Int, enabled: Boolean, onEdit: (BookSpacingChoice) -> Unit,
) {
    val density = LocalDensity.current
    val chipModifier = if (density.fontScale >= 1.5f) Modifier.fillMaxWidth().heightIn(
        min = with(density) { MaterialTheme.typography.labelLarge.lineHeight.toDp() } + 16.dp,
    ) else Modifier
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(if (global == null) "Genel: Yayıncı düzeni" else "Genel: %" + global,
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((name, selected, value) in listOf(
                Triple("Genel ayarı kullan", choice == BookSpacingChoice.Inherit, BookSpacingChoice.Inherit),
                Triple("Yayıncı düzeni", choice == BookSpacingChoice.Publisher, BookSpacingChoice.Publisher),
                Triple("Özel boşluk", choice is BookSpacingChoice.Custom,
                    BookSpacingChoice.Custom((((global ?: fallback) / 10.0).roundToInt() * 10).coerceIn(range))),
            )) {
                FilterChip(selected = selected, onClick = { if (!selected) onEdit(value) }, enabled = enabled,
                    label = { Text(name) }, modifier = chipModifier.semantics { contentDescription = label + ": " + name })
            }
        }
        if (choice is BookSpacingChoice.Custom) {
            Text(sliderLabel + " · %" + choice.percent, style = MaterialTheme.typography.titleSmall)
            Slider(value = choice.percent.toFloat(), onValueChange = { raw ->
                onEdit(BookSpacingChoice.Custom(((raw / 10).roundToInt() * 10).coerceIn(range)))
            }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first) / 10 - 1,
                enabled = enabled, modifier = Modifier.semantics { contentDescription = sliderLabel })
        }
    }
}
