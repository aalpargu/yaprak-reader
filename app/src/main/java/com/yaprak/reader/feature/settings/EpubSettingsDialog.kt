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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EpubSettingsDialog(
    state: EpubSettingsState, onEdit: (EpubSettings) -> Unit, onSave: () -> Unit,
    onDismiss: () -> Unit, onReset: () -> Unit, onReload: () -> Unit,
) {
    if (!state.open) return
    val value = state.draft
    val enabled = !state.saving && !state.loading && !state.loadFailed
    val density = LocalDensity.current
    val themeChoiceModifier = if (density.fontScale >= 1.5f) Modifier.fillMaxWidth().heightIn(
        min = with(density) { MaterialTheme.typography.labelLarge.lineHeight.toDp() } + 16.dp,
    ) else Modifier
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Okuma görünümü") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bu ayarlar EPUB ve TXT kitapları için geneldir. Kitap teması, yazı boyutu veya yazı ailesi seçilmişse ilgili kitap seçimi kullanılır. Değişiklikler kaydedince uygulanır.")
                when {
                    state.loading -> CircularProgressIndicator()
                    state.loadFailed -> {
                        Text("Ayarlar okunamadı. Mevcut kayıt değiştirilmedi.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onReload) { Text("Yeniden yükle") }
                    }
                    else -> {
                        Text("Okuma biçimi", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !value.scroll, onClick = { onEdit(value.copy(scroll = false)) },
                                enabled = enabled, label = { Text("Sayfalı") })
                            FilterChip(selected = value.scroll, onClick = { onEdit(value.copy(scroll = true)) },
                                enabled = enabled, label = { Text("Dikey kaydırma") })
                        }
                        Text("Dikey kaydırma bölüm metnini yukarı/aşağı kaydırır. Kaydedince mevcut okuma konumundan sürer; metnin ekrandaki yeri değişebilir.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Metin hizalaması", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReadingTextAlignment.entries.forEach { alignment ->
                                val label = when (alignment) {
                                    ReadingTextAlignment.PUBLISHER -> "Yayıncı düzeni"
                                    ReadingTextAlignment.START -> "Metin yönüne göre"
                                    ReadingTextAlignment.JUSTIFY -> "İki yana yasla"
                                }
                                FilterChip(selected = value.textAlignment == alignment,
                                    onClick = { onEdit(value.copy(textAlignment = alignment)) },
                                    enabled = enabled, label = { Text(label) },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Metin hizalaması: $label"
                                    })
                            }
                        }
                        Text("Metin yönüne göre, kitabın yazı yönünü izler: soldan sağa metinde sola, sağdan sola metinde sağa hizalar.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Özel hizalama bazı paragraf ve başlık stillerini değiştirir; iki yana yaslama sözcükleri heceleyebilir. Seçim yalnız desteklenen yatay metin düzenlerinde uygulanır.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Tema", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReadingTheme.entries.forEach { theme ->
                                FilterChip(selected = value.theme == theme, onClick = { onEdit(value.copy(theme = theme)) }, enabled = enabled,
                                    modifier = themeChoiceModifier,
                                    label = { Text(when (theme) { ReadingTheme.SYSTEM -> "Sistem"; ReadingTheme.LIGHT -> "Açık"; ReadingTheme.SEPIA -> "Sepya"; ReadingTheme.DARK -> "Koyu"; ReadingTheme.AMOLED -> "AMOLED siyah" }) })
                            }
                        }
                        Text("Yazı ailesi", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReadingFont.entries.forEach { font ->
                                FilterChip(selected = value.font == font, onClick = { onEdit(value.copy(font = font)) }, enabled = enabled,
                                    label = { Text(when (font) { ReadingFont.ORIGINAL -> "Kitabın yazısı"; ReadingFont.SERIF -> "Serif"; ReadingFont.SANS_SERIF -> "Sans serif" }) })
                            }
                        }
                        SettingSlider("Yazı boyutu", value.fontSizePercent, 75..200, 5, enabled) { onEdit(value.copy(fontSizePercent = it)) }
                        Text("Satır aralığı", style = MaterialTheme.typography.titleSmall)
                        FilterChip(selected = value.lineHeightPercent == null,
                            onClick = { onEdit(value.copy(lineHeightPercent = if (value.lineHeightPercent == null) 140 else null)) },
                            enabled = enabled, label = { Text("Yayıncı düzeni") })
                        if (value.lineHeightPercent != null) {
                            SettingSlider("Satır yüksekliği", value.lineHeightPercent, 100..220, 10, enabled) { onEdit(value.copy(lineHeightPercent = it)) }
                            Text("Özel satır aralığı yayıncının bazı paragraf stillerini değiştirir.", style = MaterialTheme.typography.bodySmall)
                        }
                        SettingSlider("Kenar boşluğu", value.marginsPercent, 50..200, 10, enabled) { onEdit(value.copy(marginsPercent = it)) }
                        Text("Paragraf aralığı", style = MaterialTheme.typography.titleSmall)
                        FilterChip(selected = value.paragraphSpacingPercent == null, enabled = enabled,
                            modifier = themeChoiceModifier,
                            onClick = { onEdit(value.copy(paragraphSpacingPercent = if (value.paragraphSpacingPercent == null) 100 else null)) },
                            label = { Text("Yayıncı paragraf aralığı") })
                        value.paragraphSpacingPercent?.let { spacing ->
                            SettingSlider("Paragraf boşluğu", spacing, 0..200, 10, enabled) {
                                onEdit(value.copy(paragraphSpacingPercent = it))
                            }
                        }
                        Text("Kelime aralığı", style = MaterialTheme.typography.titleSmall)
                        FilterChip(selected = value.wordSpacingPercent == null, enabled = enabled,
                            modifier = themeChoiceModifier,
                            onClick = { onEdit(value.copy(wordSpacingPercent = if (value.wordSpacingPercent == null) 20 else null)) },
                            label = { Text("Yayıncı kelime aralığı") })
                        value.wordSpacingPercent?.let { spacing ->
                            SettingSlider("Ek kelime boşluğu", spacing, 0..100, 10, enabled) {
                                onEdit(value.copy(wordSpacingPercent = it))
                            }
                        }
                        Text("Boşluk miktarı yazı boyutuna göredir. %100, bir yazı boyutuna karşılık gelir. Kelime boşluğu mevcut aralığa eklenir. Özel aralıklar kitabın paragraf düzenini değiştirebilir; yalnız desteklenen metin düzenlerinde uygulanır.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onReset, enabled = enabled) { Text("Varsayılanlara dön") }
                    }
                }
                if (state.saveFailed) Text("Ayarlar kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = enabled) { Text(if (state.saving) "Kaydediliyor…" else "Kaydet") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Vazgeç") } },
    )
}

@Composable
private fun SettingSlider(label: String, value: Int, range: IntRange, step: Int, enabled: Boolean, onValue: (Int) -> Unit) {
    Column {
        Text("$label · %$value", style = MaterialTheme.typography.titleSmall)
        Slider(value = value.toFloat(), onValueChange = { raw ->
            onValue((range.first + ((raw - range.first) / step).roundToInt() * step).coerceIn(range))
        }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first) / step - 1,
            enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}
