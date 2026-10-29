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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToLong

internal fun pageIndexForInput(input: String, pageCount: Int): Int? {
    val number = input.trim()
    if (number.isEmpty() || number.length > 10 || number.any { it !in '0'..'9' }) return null
    return number.toIntOrNull()?.takeIf { it in 1..pageCount }?.minus(1)
}

internal fun pageIndexForSliderProgress(progress: Float, pageCount: Int): Int? {
    if (pageCount < 1 || !progress.isFinite()) return null
    if (pageCount == 1) return 0
    return (progress.coerceIn(0f, 1f).toDouble() * (pageCount.toLong() - 1L))
        .roundToLong().coerceIn(0L, pageCount.toLong() - 1L).toInt()
}

internal fun sliderProgressForPageIndex(pageIndex: Int, pageCount: Int): Float {
    if (pageCount <= 1) return 0f
    return (pageIndex.coerceIn(0, pageCount - 1).toDouble() / (pageCount.toLong() - 1L)).toFloat()
}

@Composable
internal fun PageJumpDialog(pageCount: Int, currentPage: Int?, onGo: (Int) -> Boolean, onDismiss: () -> Unit) {
    val initialPage = currentPage?.takeIf { it >= 0 && it < pageCount } ?: 0
    var input by rememberSaveable(pageCount) {
        mutableStateOf(if (pageCount > 0) (initialPage.toLong() + 1L).toString() else "")
    }
    var sliderPage by rememberSaveable(pageCount) { mutableIntStateOf(initialPage) }
    var rejected by remember { mutableStateOf(false) }
    val target = pageIndexForInput(input, pageCount)
    val invalid = input.isNotEmpty() && target == null
    val displayedPage = (target ?: sliderPage).toLong() + 1L
    fun submit() {
        val index = target ?: return
        if (onGo(index)) onDismiss() else rejected = true
    }
    // Compose owns the width, avoiding repeated platform width negotiation with an editable field.
    AlertDialog(modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss, title = { Text("Sayfaya git") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bu PDF $pageCount sayfa. Dosyanın sayfa sırası kullanılır.")
            currentPage?.let { Text("Görüntülenen sayfa: ${it + 1}") }
            if (pageCount > 0) {
                Text("Hızlı seçim: $displayedPage / $pageCount")
                Slider(
                    value = sliderProgressForPageIndex(target ?: sliderPage, pageCount),
                    onValueChange = { progress ->
                        pageIndexForSliderProgress(progress, pageCount)?.let { index ->
                            sliderPage = index
                            input = (index.toLong() + 1L).toString()
                            rejected = false
                        }
                    },
                    enabled = pageCount > 1,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Hızlı sayfa seçimi"
                        stateDescription = "$displayedPage. sayfa, toplam $pageCount sayfa"
                    },
                )
            }
            OutlinedTextField(value = input, onValueChange = {
                input = if (it.length <= 32) it else it.take(32) + "…"
                pageIndexForInput(input, pageCount)?.let { index -> sliderPage = index }
                rejected = false
            },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Sayfa numarası") }, isError = invalid,
                supportingText = { Text("1–$pageCount arasında tam sayı gir.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }))
            if (rejected) Text("Bu sayfaya gidilemedi. Yeniden deneyebilirsin.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { submit() }, enabled = target != null) { Text("Git") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}
