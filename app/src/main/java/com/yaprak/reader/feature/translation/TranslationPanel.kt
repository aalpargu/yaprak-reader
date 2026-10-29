package com.yaprak.reader.feature.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaprak.reader.domain.translation.TranslationFailure

@NonSkippableComposable
@Composable internal fun TranslationPanel(state: TranslationState?, isCurrent: () -> Boolean,
    onOpen: () -> Unit, onCheck: (TranslationState) -> Unit, onDownload: (TranslationState) -> Unit,
    onTranslate: (TranslationState) -> Unit, onDelete: (TranslationState) -> Unit,
    onCopy: (TranslationState) -> Unit, onClose: (TranslationState) -> Unit,
    onTransferWord: ((TranslationState) -> Unit)? = null) {
    // Equal values can still belong to different action owners; retain the incoming reference.
    val live = remember { mutableStateOf(state, referentialEqualityPolicy()) }.apply { value = state }.value
    val gate by rememberUpdatedState(isCurrent)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("İngilizce → Türkçe çeviri", style = MaterialTheme.typography.titleMedium)
        Text("Kaynak metin İngilizce olmalı; dil otomatik algılanmaz. Makine çevirisi hatalı olabilir, sonucu kontrol et. Metin ve sonuç cihazda işlenir. Google ML Kit kullanım ve performans ölçümlerini paylaşabilir.",
            Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        @Composable fun action(label: String, tag: String, event: () -> Unit, enabled: Boolean = true) {
            key(TranslationIdentity(state), tag, event) {
                val lease = remember { TranslationActionLease() }
                DisposableEffect(lease) { onDispose { lease.active = false } }
                TextButton({ if (enabled && lease.active && gate() && live === state) event() },
                    enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
                    Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        }
        if (state == null) action("Çeviri aracını aç", "translation-open", onOpen)
        else {
            if (state.working || state.phase == TranslationPhase.DOWNLOADING) Text(when (state.phase) {
                TranslationPhase.CHECKING -> "Cihazdaki model kontrol ediliyor…"
                TranslationPhase.DOWNLOADING -> "Model indirme isteği sürüyor; Wi-Fi gerekebilir. Paneli kapatsan da indirme devam edebilir."
                TranslationPhase.DELETING -> "Türkçe çeviri modeli kaldırılıyor…"
                else -> "Seçili metin cihazda çevriliyor…"
            }, Modifier.fillMaxWidth())
            if (state.phase == TranslationPhase.MISSING) {
                Text("Türkçe çeviri modeli bu cihazda hazır değil. Dil modeli yaklaşık 30 MB yer kullanabilir. İndirme Google'dan, yalnız Wi-Fi ile yapılır. Bu düğme metni çevirmez; paneli kapatmak indirmeyi durdurmayabilir.",
                    Modifier.fillMaxWidth())
                action("Modeli Wi-Fi ile indir", "translation-download", { onDownload(state) }, !state.working)
            }
            if (state.modelsReady && !state.working) {
                action("Seçili İngilizce metni Türkçeye çevir", "translation-run", { onTranslate(state) })
                state.translatedText?.let { translated ->
                    Text("Türkçe · makine çevirisi", style = MaterialTheme.typography.labelLarge)
                    Text(translated, Modifier.fillMaxWidth())
                    action("Çeviriyi panoya kopyala", "translation-copy", { onCopy(state) })
                    if (onTransferWord != null && com.yaprak.reader.feature.vocabulary.translatedWordDraft(state) != null) {
                        Text("Kelime ve çeviri kayıt formuna taslak olarak aktarılır. Anlamı kontrol edip ayrıca kaydet.",
                            Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                        action("Kelime ve çeviriyi taslağa aktar", "translation-word", { onTransferWord(state) })
                    }
                    when (state.copyStatus) {
                        TranslationCopyStatus.NONE -> Unit
                        TranslationCopyStatus.COPIED -> Text("Çeviri panoya kopyalandı.")
                        TranslationCopyStatus.FAILED -> Text("Çeviri kopyalanamadı. Yeniden dene.", color = MaterialTheme.colorScheme.error)
                    }
                }
                Text("Modeli kaldırmak kitaplarını ve notlarını değiştirmez; yeniden çeviri için modeli tekrar indirmen gerekir.",
                    Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                action("Türkçe çeviri modelini kaldır", "translation-delete", { onDelete(state) })
            }
            state.failure?.let { Text(translationFailureText(it), Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error) }
            if (!state.working) action("Model durumunu yeniden kontrol et", "translation-check", { onCheck(state) })
            action("Çeviri panelini kapat", "translation-close", { onClose(state) })
        }
    }
}
private class TranslationActionLease { var active = true }
private class TranslationIdentity(private val value: TranslationState?) {
    override fun equals(other: Any?) = other is TranslationIdentity && value === other.value
    override fun hashCode() = System.identityHashCode(value)
}
private fun translationFailureText(reason: TranslationFailure) = when (reason) {
    TranslationFailure.INVALID_TEXT -> "Seçili metin çeviri için uygun değil."
    TranslationFailure.LIMIT -> "Çeviri metni çok uzun. En fazla 5000 karakter işlenebilir."
    TranslationFailure.MODEL_MISSING -> "Çeviri modeli artık hazır değil. Önce modeli indir."
    TranslationFailure.BUSY -> "Önceki çeviri işlemi tamamlanmayı bekliyor. Biraz sonra yeniden dene."
    TranslationFailure.MODELS -> "Cihazdaki çeviri modeli kontrol edilemedi."
    TranslationFailure.DOWNLOAD -> "Model indirilemedi. Wi-Fi bağlantısını kontrol edip yeniden dene."
    TranslationFailure.TRANSLATE -> "Motor geçerli bir çeviri üretemedi. Yeniden dene."
    TranslationFailure.DELETE -> "Türkçe çeviri modeli kaldırılamadı. Yeniden dene."
}
