package com.yaprak.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kitap görünümü") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Bu kitap için değiştirmek istediğin ayarı seç.")
                TextButton(onClick = onTheme) { Text("Kitap teması") }
                TextButton(onClick = onFontSize) { Text("Kitap yazı boyutu") }
                TextButton(onClick = onFont) { Text("Kitap yazı ailesi") }
                TextButton(onClick = onMargins) { Text("Kitap kenar boşluğu") }
                TextButton(onClick = onLineHeight) { Text("Kitap satır aralığı") }
                TextButton(onClick = onSpacing) { Text("Kitap paragraf / kelime aralığı") }
                onTextAlignment?.let { action -> TextButton(onClick = action) { Text("Kitap metin hizalaması") } }
                onReadingMode?.let { action -> TextButton(onClick = action) { Text("Kitap okuma modu") } }
                TextButton(onClick = onReset) { Text("Bu kitabın görünümünü genel ayara döndür") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}
