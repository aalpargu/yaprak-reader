package com.yaprak.reader.feature.pdfannotations

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yaprak.reader.domain.pdfannotations.*

@Composable
internal fun PdfAnnotationRoute(request: PdfAnnotationRequest, repository: PdfAnnotationRepository,
    canMutate: () -> Boolean, onDismiss: () -> Unit) {
    val model: PdfAnnotationEditorViewModel = viewModel(key="pdf-annotation-${request.id}", factory=viewModelFactory {
        initializer { PdfAnnotationEditorViewModel(request,repository) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val allowed by rememberUpdatedState(canMutate)
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(model) { onDispose { model.close() } }
    LaunchedEffect(state.completed) { if(state.completed && request.lifetime.isActive && allowed()) dismiss() }
    PdfAnnotationEditorDialog(request,state,canMutate(),
        { if(allowed()) model.editColor(it) }, { if(allowed()) model.editNote(it) },
        { if(allowed()) model.save { allowed() } }, { model.close(); dismiss() })
}

/** The body scrolls; explicit cancel/save remain reachable with large text and short landscape windows. */
@Composable
internal fun PdfAnnotationEditorDialog(request: PdfAnnotationRequest, state: PdfAnnotationEditorState, allowed: Boolean,
    onColor: (PdfHighlightColor?) -> Unit, onNote: (String) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    val live by rememberUpdatedState(state)
    val liveAllowed by rememberUpdatedState(allowed)
    val lease=remember(request.id) { PdfAnnotationUiLease() }
    DisposableEffect(lease) { lease.active=true; onDispose { lease.active=false } }
    fun fresh()=lease.active && request.lifetime.isActive && live===state && liveAllowed
    val editable=allowed && !state.busy && state.error!=PdfAnnotationError.CHANGED && !state.closed && !state.completed
    Dialog(onDismissRequest={ if(lease.active && request.lifetime.isActive) onDismiss() },properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.padding(horizontal=16.dp).widthIn(max=560.dp).fillMaxWidth(),shape=MaterialTheme.shapes.extraLarge,tonalElevation=6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max=560.dp).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).testTag("pdf-annotation-body"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(if(request.expected==null) "PDF vurgusu ve not" else "PDF kaydını düzenle",style=MaterialTheme.typography.headlineSmall)
                    Text("PDF sayfası: ${request.anchor.pageIndex.toLong()+1}",style=MaterialTheme.typography.bodySmall)
                    Text(request.text,Modifier.testTag("pdf-annotation-selection"))
                    Text("Bir renk seçebilir veya seçili metne not ekleyebilirsin. Kaydetmeden önce özgün PDF konumu yeniden doğrulanır.",style=MaterialTheme.typography.bodySmall)
                    Column(Modifier.selectableGroup()) {
                        (listOf<PdfHighlightColor?>(null)+PdfHighlightColor.entries).forEach { option -> key(state,option) {
                            Row(Modifier.fillMaxWidth().selectable(state.color==option,enabled=editable,role=Role.RadioButton,
                                onClick={ if(fresh() && editable) onColor(option) }).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                RadioButton(state.color==option,onClick=null,enabled=editable)
                                Text(option.pdfColorLabel(),Modifier.weight(1f).padding(start=12.dp))
                            }
                        } }
                    }
                    OutlinedTextField(state.note,onValueChange={ if(fresh() && editable) onNote(it) },modifier=Modifier.fillMaxWidth().testTag("pdf-annotation-note"),
                        enabled=editable,minLines=2,maxLines=5,label={ Text("Bağlı not") },isError=!state.noteValid,
                        supportingText={ Text("${state.note.length} / $MAX_PDF_ATTACHED_NOTE karakter") })
                    if(state.note.isNotEmpty()) key(state,"clear") { TextButton(onClick={ if(fresh() && editable) onNote("") },enabled=editable,modifier=Modifier.fillMaxWidth()) { Text("Notu temizle") } }
                    state.error?.let { Text(it.pdfErrorText(),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("pdf-annotation-error")) }
                }
                key(state,"cancel") { TextButton(onClick={ if(lease.active && request.lifetime.isActive && live===state) onDismiss() },
                    modifier=Modifier.fillMaxWidth().testTag("pdf-annotation-cancel")) {
                    Text(if(state.busy) "İptal et" else "Vazgeç",Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
                } }
                key(state,"save") { TextButton(onClick={ if(fresh() && state.canSave) onSave() },enabled=allowed && state.canSave,
                    modifier=Modifier.fillMaxWidth().testTag("pdf-annotation-save")) {
                    Text(if(state.busy) "Doğrulanıyor ve kaydediliyor…" else "Kaydet",Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
                } }
            }
        }
    }
}

@Composable
internal fun PdfAnnotationsRoute(request: PdfAnnotationsWindow, repository: PdfAnnotationRepository,
    canMutate: () -> Boolean, goToPage: (com.yaprak.reader.reader.PdfSourceAnchor) -> Boolean, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    val model: PdfAnnotationsViewModel=viewModel(key="pdf-annotations-${request.id}",factory=viewModelFactory { initializer { PdfAnnotationsViewModel(request,repository) } })
    val state by model.state.collectAsStateWithLifecycle()
    val allowed by rememberUpdatedState(canMutate)
    val currentState by rememberUpdatedState(state)
    val lease=remember(request.id) { PdfAnnotationUiLease() }
    DisposableEffect(model,lease) { lease.active=true; onDispose { lease.active=false; model.close() } }
    fun fresh()=lease.active && request.lifetime.isActive && allowed() && currentState===state
    state.editor?.let { editor ->
        PdfAnnotationRoute(editor,repository,{ lease.active && allowed() && model.state.value.editor===editor },{ model.closeEditor(editor) })
    }
    if(state.editor!=null) return
    Dialog(onDismissRequest={ if(lease.active && request.lifetime.isActive) { model.close(); onDismiss() } },properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.padding(horizontal=16.dp).widthIn(max=600.dp).fillMaxWidth(),shape=MaterialTheme.shapes.extraLarge,tonalElevation=6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max=620.dp).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("PDF vurguları ve notlar",style=MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).testTag("pdf-annotations-body"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(state.query,{ if(fresh()) model.search(it) },enabled=!state.busy,
                        modifier=Modifier.fillMaxWidth().testTag("pdf-annotations-search"),label={ Text("Metin veya not içinde ara") },singleLine=true)
                    if(!request.editingSupported) Text("Renk ve bağlı not düzenlemek Android 15 veya sonrasını gerektirir. Kayıtları okuyabilir, dışa aktarabilir veya silebilirsin.",style=MaterialTheme.typography.bodySmall)
                    when {
                        state.loading -> Text("Kayıtlar yükleniyor…")
                        state.failed -> { Text("PDF kayıtları okunamadı.",color=MaterialTheme.colorScheme.error); TextButton(onClick={ if(fresh()) model.reload() }) { Text("Yeniden dene") } }
                        state.entries.isEmpty() -> Text("Bu PDF için vurgu veya bağlı not kaydı yok.")
                        state.visibleEntries.isEmpty() -> Text("Aramayla eşleşen kayıt yok.")
                        else -> state.visibleEntries.forEach { record -> key(state,record.id) {
                            Column(Modifier.fillMaxWidth().testTag("pdf-annotation-row-${record.id}"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text("PDF sayfası ${record.anchor.pageIndex.toLong()+1} · ${record.highlightColor.pdfColorLabel()}",style=MaterialTheme.typography.labelLarge)
                                Text(record.text); record.attachedNote?.let { Text("Bağlı not:",style=MaterialTheme.typography.labelMedium); Text(it) }
                                val enabled=state.canAct && state.deleteCandidate==null
                                TextButton(onClick={ if(fresh() && enabled) model.openPage(record,{ allowed() },goToPage) },enabled=enabled,modifier=Modifier.fillMaxWidth()) { Text("Kaydı aç") }
                                TextButton(onClick={ if(fresh() && enabled) onCopy(record.text+record.attachedNote?.let { "\n\nBağlı not:\n$it" }.orEmpty()) },enabled=enabled,modifier=Modifier.fillMaxWidth()) { Text("Kopyala") }
                                TextButton(onClick={ if(fresh() && enabled) model.edit(record) },enabled=enabled && request.editingSupported && record.revision<Long.MAX_VALUE,modifier=Modifier.fillMaxWidth()) { Text("Düzenle") }
                                TextButton(onClick={ if(fresh() && enabled) model.askDelete(record) },enabled=enabled,modifier=Modifier.fillMaxWidth()) { Text("Sil") }
                                HorizontalDivider()
                            }
                        } }
                    }
                    state.deleteCandidate?.let { expected -> key(state,"delete") {
                        Text("Bu vurgu ve bağlı not silinsin mi?",color=MaterialTheme.colorScheme.error)
                        TextButton(onClick={ if(fresh()) model.cancelDelete(expected) },enabled=!state.busy,modifier=Modifier.fillMaxWidth()) { Text("Vazgeç") }
                        TextButton(onClick={ if(fresh()) model.delete(expected) { allowed() } },enabled=!state.busy,modifier=Modifier.fillMaxWidth().testTag("pdf-annotation-confirm-delete")) { Text("Kaydı sil") }
                    } }
                    if(state.busy) Text("İşlem sürüyor…")
                    state.error?.let { Text(it.pdfErrorText(),color=MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick={ if(lease.active && request.lifetime.isActive) { model.close(); onDismiss() } },modifier=Modifier.fillMaxWidth().testTag("pdf-annotations-close")) {
                    Text("Kapat",Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
                }
            }
        }
    }
}
private class PdfAnnotationUiLease { var active=true }
internal fun PdfHighlightColor?.pdfColorLabel()=when(this) {
    null -> "Renk yok"; PdfHighlightColor.YELLOW -> "Sarı"; PdfHighlightColor.GREEN -> "Yeşil"; PdfHighlightColor.BLUE -> "Mavi"; PdfHighlightColor.PINK -> "Pembe"
}
private fun PdfAnnotationError.pdfErrorText()=when(this) {
    PdfAnnotationError.CHANGED -> "Bu kayıt değişti veya bu seçim zaten kayıtlı. Kayıt listesini yeniden aç."
    PdfAnnotationError.LIMIT -> "Bu kitap için en fazla 1000 PDF kaydı saklanabilir."
    PdfAnnotationError.SOURCE -> "Özgün PDF veya kayıt doğrulanamadı. İşlemi yeniden deneyebilirsin."
}
