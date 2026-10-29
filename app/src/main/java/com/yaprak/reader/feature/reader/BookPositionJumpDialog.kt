package com.yaprak.reader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun BookPositionJumpDialog(state: BookPositionJumpState, onEdit: (String) -> Unit,
    onSubmit: () -> Unit, onDismiss: () -> Unit) {
    if (!state.open) return
    fun submit() { if (state.canSubmit) onSubmit() }
    AlertDialog(
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text("Kitap yüzdesine git") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Yüzde, kitaptaki yaklaşık konumu seçer. Sayfa numarası veya okunmuş metin değildir.")
                OutlinedTextField(value = state.input, onValueChange = onEdit,
                    modifier = Modifier.fillMaxWidth().testTag("book-position-input"),
                    singleLine = true, enabled = !state.resolving,
                    label = { Text("Yüzde") },
                    isError = state.input.isNotEmpty() && bookPercentForInput(state.input) == null,
                    supportingText = { Text("0–100 arasında tam sayı gir.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }))
                if (state.resolving) Text("Kitap konumu bulunuyor…",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = state.canSubmit) { Text("Git") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
