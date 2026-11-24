package com.yaprak.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Opens one independently saved EPUB book preference. */
@Composable
internal fun BookAppearanceDialog(
    open: Boolean,
    onTheme: () -> Unit,
    onFontSize: () -> Unit,
    onFont: () -> Unit,
    onMargins: () -> Unit,
    onLineHeight: () -> Unit,
    onSpacing: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    onTextAlignment: (() -> Unit)? = null,
    onReadingMode: (() -> Unit)? = null,
    busy: Boolean = false,
    focusTarget: @Composable (String) -> Modifier = { Modifier },
    canOpen: Boolean = true,
) {
    if (!open) return
    val lease = remember { BookAppearanceLease() }
    val liveBusy by rememberUpdatedState(busy)
    val liveCanOpen by rememberUpdatedState(canOpen)
    DisposableEffect(lease) { onDispose { lease.active = false } }
    fun act(action: () -> Unit) { if (lease.active && !liveBusy) action() }
    fun launch(action: () -> Unit) { if (liveCanOpen) act(action) }
    AlertDialog(
        onDismissRequest = { act(onDismiss) },
        title = { Text("Kitap görünümü") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (busy) "Görünüm uygulanıyor…" else if (!canOpen)
                    "Okuyucu henüz hazır değil. Bu pencereyi kapatıp hata bildiriminden yeniden deneyebilirsin."
                    else "Bu kitap için değiştirmek istediğin ayarı seç.")
                TextButton(onClick = { launch(onTheme) }, enabled = !busy && canOpen, modifier = focusTarget("book_theme")) { Text("Kitap teması") }
                TextButton(onClick = { launch(onFontSize) }, enabled = !busy && canOpen, modifier = focusTarget("book_font_size")) { Text("Kitap yazı boyutu") }
                TextButton(onClick = { launch(onFont) }, enabled = !busy && canOpen, modifier = focusTarget("book_font")) { Text("Kitap yazı ailesi") }
                TextButton(onClick = { launch(onMargins) }, enabled = !busy && canOpen, modifier = focusTarget("book_margins")) { Text("Kitap kenar boşluğu") }
                TextButton(onClick = { launch(onLineHeight) }, enabled = !busy && canOpen, modifier = focusTarget("book_line_height")) { Text("Kitap satır aralığı") }
                TextButton(onClick = { launch(onSpacing) }, enabled = !busy && canOpen, modifier = focusTarget("book_spacing")) { Text("Kitap paragraf / kelime aralığı") }
                onTextAlignment?.let { action -> TextButton(onClick = { launch(action) }, enabled = !busy && canOpen, modifier = focusTarget("book_alignment")) { Text("Kitap metin hizalaması") } }
                onReadingMode?.let { action -> TextButton(onClick = { launch(action) }, enabled = !busy && canOpen, modifier = focusTarget("book_reading_mode")) { Text("Kitap okuma modu") } }
                TextButton(onClick = { launch(onReset) }, enabled = !busy && canOpen, modifier = focusTarget("book_reset")) { Text("Bu kitabın görünümünü genel ayara döndür") }
            }
        },
        confirmButton = { TextButton(onClick = { act(onDismiss) }, enabled = !busy) { Text("Kapat") } },
    )
}

private class BookAppearanceLease { var active = true }
