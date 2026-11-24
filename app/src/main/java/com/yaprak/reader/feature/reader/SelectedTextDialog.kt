package com.yaprak.reader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import com.yaprak.reader.R
import com.yaprak.reader.reader.EpubSourceAnchor
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceRangeAnchors
import java.util.UUID

/** Ephemeral preview. PDF's actual source page is a caption, never a progress or durable anchor. */
sealed interface SelectedTextPreview {
    val sessionId: String
    data class Loading(override val sessionId: String = UUID.randomUUID().toString(), val pdfOcr: Boolean = false) : SelectedTextPreview
    data class Ready(val text: String, val copyStatus: CopyStatus = CopyStatus.NONE,
        val rangeCandidate: com.yaprak.reader.reader.SelectionRangeCandidate? = null,
        val sourceAnchor: EpubSourceAnchor? = null,
        override val sessionId: String = UUID.randomUUID().toString(),
        val pdfPageIndex: Int? = null, val sourceSelectionId: String? = null,
        val pdfSourceAnchor: com.yaprak.reader.reader.PdfSourceAnchor? = null,
        val pdfOcr: Boolean = false) : SelectedTextPreview
    data class Error(val message: String, override val sessionId: String = UUID.randomUUID().toString()) : SelectedTextPreview
}

enum class CopyStatus { NONE, COPIED, FAILED }

internal fun eligiblePdfAnnotationAnchor(preview: SelectedTextPreview.Ready): com.yaprak.reader.reader.PdfSourceAnchor? =
    preview.pdfSourceAnchor?.takeIf { !preview.pdfOcr && it.pageIndex == preview.pdfPageIndex && preview.sourceAnchor == null &&
        runCatching { com.yaprak.reader.domain.pdfannotations.validatePdfAnnotationText(preview.text,
            com.yaprak.reader.domain.pdfannotations.MAX_PDF_ANNOTATION_TEXT) }.isSuccess }

/** The source match covers this exact selected text, not a future rendered WebView range. */
internal fun eligiblePassageAnchor(text: String, anchor: EpubSourceAnchor?): EpubSourceAnchor? =
    anchor?.takeIf { text.isNotBlank() && text.length in 1..4096 && it.resourceHref.isNotBlank() &&
        SourceRangeAnchors.validUtf16(text) && when (it) {
            is SourceTextAnchor -> it.start >= 0 && it.end > it.start && it.end <= 4096 &&
                it.end - it.start == text.length
            is SourceRangeAnchor -> SourceRangeAnchors.isValid(it)
        } }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectedTextDialog(preview: SelectedTextPreview?, onDismiss: () -> Unit, onCopy: () -> Unit,
    onTransferWord: (() -> Unit)? = null, onTransferNote: (() -> Unit)? = null,
    onTransferPassage: (() -> Unit)? = null, onAnnotate: (() -> Unit)? = null,
    showAnnotationHint: Boolean = false, toolsState: SelectedTextToolsState? = null,
    onWebSearch: (() -> Unit)? = null, onTextTool: ((SelectedTextTool) -> Unit)? = null,
    onRetryTools: (() -> Unit)? = null,
    dictionaryState: com.yaprak.reader.feature.dictionary.DictionaryLookupState? = null,
    onDictionary: (() -> Unit)? = null,
    onRetryDictionary: ((com.yaprak.reader.feature.dictionary.DictionaryLookupState) -> Unit)? = null,
    assistantContent: (@Composable () -> Unit)? = null, pdfAnnotationHint: Boolean = false,
    translationContent: (@Composable () -> Unit)? = null, onBookSearch: (() -> Unit)? = null) {
    val live by rememberUpdatedState(preview)
    val liveTools by rememberUpdatedState(toolsState)
    val liveWeb by rememberUpdatedState(onWebSearch)
    val liveProcess by rememberUpdatedState(onTextTool)
    val liveRetry by rememberUpdatedState(onRetryTools)
    val liveBookSearch by rememberUpdatedState(onBookSearch)
    if (preview == null) return
    key(preview.sessionId) {
      val lease = remember { SelectedTextDialogLease() }
      DisposableEffect(lease) { lease.active = true; onDispose { lease.active = false } }
      fun fresh() = lease.active && live === preview
      Dialog(onDismissRequest = { if (fresh()) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
          Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("selected-text-body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when (preview) {
                    is SelectedTextPreview.Ready -> if (preview.pdfOcr) "OCR metni" else "Seçili metin"
                    is SelectedTextPreview.Loading -> if (preview.pdfOcr) "Sayfa metni tanınıyor…" else "Seçim alınıyor…"
                    is SelectedTextPreview.Error -> "Metin gösterilemedi"
                }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                when (preview) {
                    is SelectedTextPreview.Loading -> Text(if (preview.pdfOcr)
                        "Sayfa cihazda işleniyor. Kapatmak talebi iptal eder; motorun kaynakları bırakması biraz sürebilir."
                        else "Özgün metin alınıyor. Kapatmak işlemi iptal eder.", Modifier.fillMaxWidth())
                    is SelectedTextPreview.Ready -> {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                            Text(preview.text, Modifier.fillMaxWidth().padding(12.dp), maxLines = 6,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif))
                        }
                        preview.pdfPageIndex?.let { Text("Sayfa ${it + 1}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (preview.pdfOcr) Text("OCR sonucu hatalı olabilir; kullanmadan önce kontrol et. Bu metin özgün PDF metin seçimi veya kesin vurgu kaynağı değildir.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val canAnnotate = !preview.pdfOcr && onAnnotate != null &&
                            (eligiblePassageAnchor(preview.text, preview.sourceAnchor) != null || eligiblePdfAnnotationAnchor(preview) != null)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (canAnnotate) SelectionChip(stringResource(R.string.annotation_action), Icons.Default.Create) { if (fresh()) onAnnotate?.invoke() }
                            if (onTransferNote != null && selectedNoteCandidate(preview.text) != null)
                                SelectionChip("Not al", Icons.Default.Edit) { if (fresh()) onTransferNote() }
                            if (onTransferWord != null) SelectionChip("Kelimelerime ekle", Icons.Default.Favorite) { if (fresh()) onTransferWord() }
                            if (!canAnnotate && !preview.pdfOcr && onTransferPassage != null && eligiblePassageAnchor(preview.text, preview.sourceAnchor) != null)
                                SelectionChip("Pasaja ekle", Icons.Default.Add) { if (fresh()) onTransferPassage() }
                            if (onBookSearch != null) key(SelectedTextIdentityKey(preview), onBookSearch) {
                                val actionLease = selectedTextActionLease()
                                SelectionChip("Kitapta ara", Icons.Default.Search, Modifier.testTag("selected-text-book-search")) {
                                    if (actionLease.active && fresh() && liveBookSearch === onBookSearch) onBookSearch()
                                }
                            }
                            if (onWebSearch != null) key(SelectedTextIdentityKey(preview), SelectedTextIdentityKey(toolsState), onWebSearch) {
                                val actionLease = selectedTextActionLease()
                                SelectionChip(stringResource(R.string.selected_tools_web),Icons.Default.Search, Modifier.testTag("selected-text-web")) {
                                    if (actionLease.active && fresh() && liveTools === toolsState && liveWeb === onWebSearch) onWebSearch()
                                }
                            }
                        }
                        if (!canAnnotate && showAnnotationHint && !preview.pdfOcr) Text(if (pdfAnnotationHint)
                            "Bu seçim vurgulanamıyor; metni yeniden seçmeyi dene."
                            else stringResource(R.string.annotation_anchor_unavailable), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (preview.copyStatus == CopyStatus.FAILED) Text("Panoya kopyalanamadı. Yeniden deneyin.", color = MaterialTheme.colorScheme.error)
                        if (onDictionary != null) com.yaprak.reader.feature.dictionary.DictionaryPanel(
                            dictionaryState?.takeIf { it.requestId == preview.sessionId },
                            isCurrent = { fresh() }, onLookup = { if (fresh()) onDictionary() },
                            onRetry = { state -> if (fresh()) onRetryDictionary?.invoke(state) })
                        translationContent?.invoke()
                        if (eligibleEpubAssistantPreview(preview) || eligiblePdfAssistantAnchor(preview) != null) assistantContent?.invoke()
                        if (onTextTool != null) toolsState?.takeIf { it.requestId == preview.sessionId }?.let { state ->
                            var othersOpen by remember { mutableStateOf(false) }
                            TextButton({ othersOpen = !othersOpen }) { Text(if (othersOpen) "Diğer uygulamaları gizle" else "Diğer uygulamalarla aç") }
                            if (othersOpen) {
                                if (state.launchFailure != null) Text(stringResource(selectedToolFailureText(state.launchFailure)),
                                    Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                                Text(stringResource(R.string.selected_tools_transfer_help), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                when {
                                    state.loading -> Text(stringResource(R.string.selected_tools_loading), Modifier.fillMaxWidth())
                                    state.discoveryFailure != null -> {
                                        Text(stringResource(selectedToolFailureText(state.discoveryFailure)), Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.error)
                                        if (onRetryTools != null) key(SelectedTextIdentityKey(state), onRetryTools) {
                                            val actionLease = selectedTextActionLease()
                                            TextButton({
                                                if (actionLease.active && fresh() && liveTools === state && liveRetry === onRetryTools) onRetryTools()
                                            }, Modifier.fillMaxWidth().testTag("selected-tools-retry")) {
                                                Text(stringResource(R.string.selected_tools_retry), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                            }
                                        }
                                    }
                                    state.tools.isEmpty() -> Text(stringResource(R.string.selected_tools_empty), Modifier.fillMaxWidth())
                                    else -> state.tools.forEach { tool -> key(SelectedTextIdentityKey(state), tool.packageName, tool.activityName, onTextTool) {
                                        val actionLease = selectedTextActionLease()
                                        TextButton({
                                            if (actionLease.active && fresh() && liveTools === state && liveProcess === onTextTool) onTextTool(tool)
                                        }, Modifier.fillMaxWidth().testTag("selected-tool-${tool.packageName}/${tool.activityName}")) {
                                            Text(tool.label, Modifier.fillMaxWidth())
                                        }
                                    } }
                                }
                            }
                        }
                    }
                    is SelectedTextPreview.Error -> Text(preview.message, color = MaterialTheme.colorScheme.error)
                }
            }
            // Copy and close stay pinned below the scrolling body, whatever panels are open above.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preview is SelectedTextPreview.Ready) key("copy", SelectedTextIdentityKey(preview), onCopy) {
                    val actionLease = selectedTextActionLease()
                    FilledTonalButton({ if (actionLease.active && fresh()) onCopy() }, Modifier.weight(1f)) {
                        Text(when (preview.copyStatus) {
                            CopyStatus.COPIED -> "Kopyalandı"; CopyStatus.FAILED -> "Yeniden kopyala"; CopyStatus.NONE -> "Kopyala"
                        }, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
                key("close", SelectedTextIdentityKey(preview), onDismiss) {
                    val actionLease = selectedTextActionLease()
                    TextButton(onClick = { if (actionLease.active && fresh()) onDismiss() }, Modifier.weight(1f)) {
                        Text("Kapat", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                }
            }
          }
        }
      }
    }
}

private class SelectedTextDialogLease { var active = true }
@Composable
private fun selectedTextActionLease(): SelectedTextDialogLease {
    val lease = remember { SelectedTextDialogLease() }
    DisposableEffect(lease) { onDispose { lease.active = false } }
    return lease
}
private class SelectedTextIdentityKey(private val value: Any?) {
    override fun equals(other: Any?) = other is SelectedTextIdentityKey && value === other.value
    override fun hashCode() = System.identityHashCode(value)
}
private fun selectedToolFailureText(failure: SelectedTextToolFailure): Int = when (failure) {
    SelectedTextToolFailure.INVALID_TEXT -> R.string.selected_tools_invalid_text
    SelectedTextToolFailure.NO_HANDLER -> R.string.selected_tools_unavailable
    SelectedTextToolFailure.DENIED -> R.string.selected_tools_denied
    SelectedTextToolFailure.LIMIT -> R.string.selected_tools_limit
    SelectedTextToolFailure.SOURCE -> R.string.selected_tools_failed
}

@Composable
private fun SelectionChip(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick, modifier, contentPadding = PaddingValues(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}
