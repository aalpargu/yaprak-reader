package com.yaprak.reader.feature.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.domain.statistics.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun BookCompletionDialog(state: BookCompletionState, onRetry: () -> Unit,
    onDismiss: () -> Unit, allowed: () -> Boolean = { true }) {
    val currentState by rememberUpdatedState(state)
    AlertDialog(
        onDismissRequest = { if (allowed()) onDismiss() },
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Kitap bitirme geçmişi") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("completion-history"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Kitabı Tamamlandı yaptığın an kaydedilir. Aynı durumu yeniden kaydetmek ek kayıt oluşturmaz. Yeniden okuyup tekrar Tamamlandı yaparsan yeni bitirme kaydı oluşur.")
                }
                item {
                    Text("Önceden tamamlanan kitapların tarihi bilinmiyor; onlara tarih eklenmez. Bu kütüphanedeki kayıtlar tam yedekte korunur. Kitap silindiğinde ilgili geçmiş de silinir.")
                }
                when {
                    state.loading -> item { Column {
                        CircularProgressIndicator(); Text("Geçmiş yükleniyor…")
                    } }
                    state.failure != null -> item { Column {
                        Text("Kitap bitirme geçmişi okunamadı.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { if (allowed() && currentState.failure != null) onRetry() }) {
                            Text("Yeniden yükle")
                        }
                    } }
                    state.snapshot != null -> {
                        val snapshot = state.snapshot
                        item { Column {
                            Text("Kayıtlı bitirme: " + snapshot.total)
                            if (snapshot.total == 0) Text("Henüz tarihli bitirme kaydı yok.")
                            if (snapshot.total > BOOK_COMPLETION_VISIBLE_LIMIT)
                                Text("En yeni " + BOOK_COMPLETION_VISIBLE_LIMIT + " kayıt gösteriliyor.")
                            Text("Başlık ve yazar güncel kütüphaneden gelir. Tarih, kaydedildiği saat diliminde gösterilir.")
                        } }
                        items(snapshot.entries, key = { it.event.id }) { entry ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                    entry.authors?.let { Text(it) }
                                    val time = Instant.ofEpochMilli(entry.event.completedAt).atZone(ZoneId.of(entry.event.zoneId))
                                    Text(DateTimeFormatter.ofPattern("d MMMM uuuu, HH:mm", Locale.forLanguageTag("tr-TR")).format(time))
                                    Text(entry.event.zoneId, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (allowed()) onDismiss() }) { Text("Kapat") } },
    )
}
