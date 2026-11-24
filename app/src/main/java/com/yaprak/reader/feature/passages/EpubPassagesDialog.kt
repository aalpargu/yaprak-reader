package com.yaprak.reader.feature.passages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import com.yaprak.reader.feature.reader.readerDialogReturnFocusTarget
import com.yaprak.reader.feature.reader.rememberReaderPanelAdmission
import android.os.Bundle
import com.yaprak.reader.feature.reader.ReaderDraftSnapshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.passages.EpubPassageRepository
import com.yaprak.reader.reader.EpubSourceAnchor
import com.yaprak.reader.R
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun EpubPassagesRoute(
    book: Book,
    repository: EpubPassageRepository,
    onDismiss: () -> Unit,
    prefillText: String? = null,
    prefillAnchor: EpubSourceAnchor? = null,
    onOpenChapter: (suspend (EpubPassage, () -> Boolean) -> Boolean)? = null,
    onCopy: ((text: String) -> Unit)? = null,
    canMutate: () -> Boolean = { true },
    restoration: Bundle? = null,
    onRestorationChange: (Bundle?) -> Unit = {},
    canInteract: () -> Boolean = canMutate,
    presentationEpoch: Long = 0L,
) {
    if (book.format != BookFormat.EPUB) return
    val model: EpubPassagesViewModel = viewModel(key = "epub-passages-${book.id}-${book.sha256}", factory = viewModelFactory {
        initializer { EpubPassagesViewModel(book.id, book.sha256, repository) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sessionId = remember(model) { UUID.randomUUID().toString() }
    val restored = remember(model, sessionId) { ReaderDraftSnapshot.fromBundle(restoration) }
    var restorationHandled by remember(model, sessionId) { mutableStateOf(restored == null) }
    val listState = rememberLazyListState()
    val snapshotChanged by rememberUpdatedState(onRestorationChange)
    fun publishSnapshot() {
        if (restorationHandled) snapshotChanged(model.snapshot(listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset).toBundle())
    }
    SideEffect { publishSnapshot() }
    LaunchedEffect(model, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { publishSnapshot() }
    }
    val childKey = state.pendingEdit?.let { "edit-${it.id}" } ?: state.pendingDelete?.let { "delete-${it.id}" } ?: "base"
    val admitted = rememberReaderPanelAdmission(childKey = "$presentationEpoch:$childKey", canMutate = canInteract)
    val liveDismiss by rememberUpdatedState(onDismiss)
    fun freshInteraction() = model.state.value.sessionId == sessionId && admitted()
    fun fresh() = freshInteraction() && canMutate()
    var openingChapterId by remember(book.id, book.sha256) { mutableStateOf<String?>(null) }
    var navigationError by remember(book.id, book.sha256) { mutableStateOf<String?>(null) }
    var openingChapterJob by remember(model) { mutableStateOf<Job?>(null) }
    val writable = canMutate()
    LaunchedEffect(writable) {
        if (!writable) {
            openingChapterJob?.cancel()
            openingChapterJob = null
            openingChapterId = null
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                openingChapterJob?.cancel()
                openingChapterJob = null
                openingChapterId = null
                navigationError = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); openingChapterJob?.cancel() }
    }
    // A deliberately new route revokes old callbacks. Recreation restores bounded user input
    // only after the Activity has revalidated the same book and this model has loaded current rows.
    DisposableEffect(model, sessionId) {
        model.startSession(sessionId)
        onDispose { model.releaseSession(sessionId) }
    }
    LaunchedEffect(model, sessionId, state.loading, state.loadFailed, state.busy) {
        if (!restorationHandled && !state.loading && !state.loadFailed && !state.busy) {
            val saved = checkNotNull(restored)
            // Apply the caller position while the loaded list still owns the layout, before
            // the restored child editor suspends its Window.
            listState.scrollToItem(saved.listIndex, saved.listOffset)
            if (model.restorePresentation(saved)) restorationHandled = true
        }
    }
    LaunchedEffect(prefillText, prefillAnchor, model) {
        if (fresh() && prefillText != null && prefillAnchor != null) model.prefill(prefillText, prefillAnchor)
    }
    val openChapter: ((String) -> Unit)? = onOpenChapter?.let { navigate ->
        { id: String ->
            val current = model.state.value
            val entry = current.entries.singleOrNull { it.id == id }
            if (fresh() && entry != null && !current.loading && !current.loadFailed && !current.busy &&
                current.pendingDelete == null && current.pendingEdit == null && openingChapterId == null) {
                openingChapterId = id
                navigationError = null
                openingChapterJob?.cancel()
                val next = scope.launch(start = CoroutineStart.LAZY) {
                    val requestJob = checkNotNull(currentCoroutineContext()[Job])
                    var completed = false
                    val accepted = try { navigate(entry) {
                        if (completed || !requestJob.isActive || openingChapterJob !== requestJob ||
                            openingChapterId != id || !freshInteraction() || !canMutate() ||
                            model.state.value.entries.singleOrNull { it.id == id } != entry) false
                        else {
                            completed = true
                            model.clearSession()
                            liveDismiss()
                            true
                        }
                    } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                    currentCoroutineContext().ensureActive()
                    if (completed) return@launch
                    if (!freshInteraction()) return@launch
                    if (!canMutate()) {
                        openingChapterId = null
                        navigationError = "Okuyucu hazır değil. Pencereyi kapatıp hata bildiriminden yeniden deneyebilirsin."
                        return@launch
                    }
                    // A changed row invalidates the result, but must still release this session's
                    // navigation gate so its refreshed records and close action remain usable.
                    if (model.state.value.entries.singleOrNull { it.id == id } != entry) {
                        openingChapterId = null
                        navigationError = stringChangedRecord
                        return@launch
                    }
                    if (!accepted) {
                        openingChapterId = null
                        navigationError = "Bölüm doğrulanamadı veya açılamadı. Pasajın tam yeri gösterilemez."
                    }
                }
                // Register ownership before even a synchronous navigator can complete this request.
                openingChapterJob = next
                next.start()
            }
        }
    }
    val copyPassage: ((String) -> Unit)? = onCopy?.let { copyText ->
        { id: String ->
            val current = model.state.value
            val entry = current.entries.singleOrNull { it.id == id }
            check(fresh() && entry != null && !current.loading && !current.loadFailed && !current.busy &&
                current.pendingDelete == null && current.pendingEdit == null && openingChapterId == null) {
                "Passage is unavailable for copying"
            }
            copyText(entry.text)
        }
    }
    EpubPassagesDialog(state, { if (fresh() && openingChapterId == null) model.save() }, { if (freshInteraction()) model.reload() },
        { if (fresh() && openingChapterId == null) model.requestDelete(it) }, { if (fresh()) model.delete() },
        { if (freshInteraction()) { model.cancelDelete(); publishSnapshot() } }, {
            val current = model.state.value
            if (freshInteraction() && !current.busy && current.pendingDelete == null && current.pendingEdit == null && openingChapterId == null) {
                model.clearSession()
                liveDismiss()
            }
        }, openChapter, openingChapterId, navigationError, copyPassage,
        { if (freshInteraction()) { model.search(it); publishSnapshot() } }, { if (freshInteraction()) { model.clearSearch(); publishSnapshot() } }, { if (freshInteraction()) { model.setSort(it); publishSnapshot() } },
        onEdit = { if (fresh() && openingChapterId == null) { model.requestEdit(it); publishSnapshot() } }, listState = listState, canWrite = writable,
        onDiscardRecoveredNote = { note, revision -> if (freshInteraction()) { model.discardRecoveredNote(note, revision); publishSnapshot() } },
        onDiscardAdditionalRecoveredEdit = { if (freshInteraction()) { model.discardAdditionalRecoveredEdit(it); publishSnapshot() } })
    state.pendingEdit?.let { expected ->
        EpubAnnotationDialog(state.sessionId, expected.text, state.editColor, state.editNote, state.busy,
            state.canUpdate && writable, state.editRevision, failed = state.message != null,
            changed = state.editChanged, editing = true,
            onColor = { if (freshInteraction()) { model.editColor(it); publishSnapshot() } }, onNote = { if (freshInteraction()) { model.editNote(it); publishSnapshot() } },
            onSave = { if (fresh()) { model.update(); publishSnapshot() } }, onDismiss = { if (freshInteraction()) { model.cancelEdit(); publishSnapshot() } },
            readerUnavailable = !writable, recoveryContent = {
                state.recoveredNote?.let { note ->
                    RecoveredPassageNoteContent(note, state.recoveredColor, state.recoveryRevision, state.busy, state.sessionId) { text, revision ->
                        if (freshInteraction()) { model.discardRecoveredNote(text, revision); publishSnapshot() }
                    }
                }
                state.additionalRecoveredEdit?.let { recovered ->
                    RecoveredPassageEditContent(recovered, state.busy, state.sessionId) {
                        if (freshInteraction()) { model.discardAdditionalRecoveredEdit(it); publishSnapshot() }
                    }
                }
            })
    }
}

@Composable
private fun RecoveredPassageNoteContent(note: String, color: String, revision: Long, busy: Boolean, sessionId: String,
    onDiscard: (String, Long) -> Unit) {
    key(sessionId, note, color, revision) {
        val admitted = rememberReaderPanelAdmission("$sessionId:$revision") { true }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Korunan not taslağı", style = MaterialTheme.typography.titleSmall)
            Text("Başka bir vurgu veya notu düzenlemeden önce bu taslağı kopyalayıp sil.", style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.text.selection.SelectionContainer { Text(note, Modifier.testTag("epub-passage-recovered-note")) }
            RecoveredPassageColor(color, "epub-passage-recovered-color")
            TextButton(onClick = { if (admitted() && !busy) onDiscard(note, revision) }, enabled = !busy,
                modifier = Modifier.testTag("epub-passage-recovered-discard")) { Text("Korunan taslağı sil") }
        }
    }
}

@Composable
private fun RecoveredPassageEditContent(recovered: RecoveredPassageEdit, busy: Boolean, sessionId: String,
    onDiscard: (RecoveredPassageEdit) -> Unit) {
    key(sessionId, recovered.id) {
        val admitted = rememberReaderPanelAdmission("$sessionId:${recovered.id}") { true }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diğer korunan not taslağı", style = MaterialTheme.typography.titleSmall)
            Text("Başka bir vurgu veya notu düzenlemeden önce bu taslağı kopyalayıp sil.", style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(recovered.note, Modifier.testTag("epub-passage-additional-recovered-note"))
            }
            RecoveredPassageColor(recovered.color, "epub-passage-additional-recovered-color")
            TextButton(onClick = { if (admitted() && !busy) onDiscard(recovered) }, enabled = !busy,
                modifier = Modifier.testTag("epub-passage-additional-recovered-discard")) { Text("Diğer korunan taslağı sil") }
        }
    }
}

@Composable
private fun RecoveredPassageColor(color: String, tag: String) {
    if (color.isNotEmpty()) {
        val value = EpubHighlightColor.entries.firstOrNull { it.name == color }
        val label = if (value == null) color else value.colorLabel()
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text("Korunan vurgu rengi: $label", Modifier.testTag(tag), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val stringChangedRecord = "Kayıt bu sırada değişti. Güncel kaydı yeniden aç."

private data class PassageCopyFeedback(val entryId: String, val succeeded: Boolean)

@Composable
internal fun EpubPassagesDialog(
    state: EpubPassagesState,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDismiss: () -> Unit,
    onOpenChapter: ((String) -> Unit)? = null,
    openingChapterId: String? = null,
    navigationError: String? = null,
    onCopy: ((passageId: String) -> Unit)? = null,
    onSearch: (String) -> Unit = {},
    onClearSearch: () -> Unit = {},
    onSort: (EpubPassageSort) -> Unit = {},
    onEdit: ((EpubPassage) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    canWrite: Boolean = true,
    onDiscardRecoveredNote: (String, Long) -> Unit = { _, _ -> },
    onDiscardAdditionalRecoveredEdit: (RecoveredPassageEdit) -> Unit = {},
) {
    val live by rememberUpdatedState(state)
    val liveNavigationBusy by rememberUpdatedState(openingChapterId != null)
    val navigationBusy = openingChapterId != null
    val filtersEnabled = !state.loading && !state.loadFailed && !state.busy &&
        state.pendingDelete == null && state.pendingEdit == null && !navigationBusy
    var sortExpanded by remember { mutableStateOf(false) }
    var suspendedChild by remember(state.sessionId) { mutableStateOf<String?>(null) }
    var returnFocus by remember(state.sessionId) { mutableStateOf<String?>(null) }
    val child = state.pendingEdit?.let { "edit-${it.id}" } ?: state.pendingDelete?.let { "delete-${it.id}" }
    LaunchedEffect(child, canWrite, filtersEnabled, state.visibleEntries) {
        if (child != null) { suspendedChild = child; returnFocus = null }
        else {
            suspendedChild?.let { previous -> returnFocus = previous; suspendedChild = null }
            returnFocus?.takeIf { it != "close" }?.let { target ->
                val id = target.substringAfter('-')
                if (!canWrite || !filtersEnabled || state.visibleEntries.none { it.id == id }) returnFocus = "close"
            }
        }
    }
    val sortItemIndex = 2 + (if (!canWrite) 1 else 0) + (if (state.recoveredNote != null) 1 else 0) +
        (if (state.additionalRecoveredEdit != null) 1 else 0) + (if (state.draft != null) 1 else 0) +
        1 + (if (state.query.isNotEmpty()) 1 else 0)
    LaunchedEffect(filtersEnabled) { if (!filtersEnabled) sortExpanded = false }
    LaunchedEffect(sortExpanded, sortItemIndex) {
        if (sortExpanded) listState.scrollToItem(sortItemIndex)
    }
    var copyFeedback by remember(state.entries) { mutableStateOf<PassageCopyFeedback?>(null) }
    key(state.sessionId) {
    fun usable() = live.sessionId == state.sessionId && !live.loading && !live.loadFailed && !live.busy &&
        live.pendingDelete == null && live.pendingEdit == null && !liveNavigationBusy
    if (state.pendingEdit == null && state.pendingDelete == null) Dialog(onDismissRequest = { if (usable()) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
          shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("epub-passages-list"), state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.annotations_title), style = MaterialTheme.typography.headlineSmall) }
                item { Text(stringResource(R.string.annotations_list_help)) }
                if (!canWrite) item { Text(readerUnavailableMessage, color = MaterialTheme.colorScheme.error) }
                state.recoveredNote?.let { note -> item {
                    RecoveredPassageNoteContent(note, state.recoveredColor, state.recoveryRevision, state.busy, state.sessionId) { text, revision ->
                        if (usable() && live.recoveredNote == text && live.recoveryRevision == revision) onDiscardRecoveredNote(text, revision)
                    }
                } }
                state.additionalRecoveredEdit?.let { recovered -> item {
                    RecoveredPassageEditContent(recovered, state.busy, state.sessionId) {
                        if (usable() && live.additionalRecoveredEdit === it) onDiscardAdditionalRecoveredEdit(it)
                    }
                } }
                state.draft?.let { draft ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Kaydedilmemiş seçim", style = MaterialTheme.typography.titleSmall)
                            Text(draft.text, modifier = Modifier.testTag("epub-passage-draft"))
                            key(draft) {
                                Button(onClick = { if (usable() && canWrite && live.draft == draft && live.canSave) onSave() }, enabled = canWrite && state.canSave && !navigationBusy,
                                    modifier = Modifier.testTag("epub-passage-save")) { Text("Pasajı kaydet") }
                            }
                            if (state.alreadySaved) Text("Bu pasaj zaten kayıtlı.")
                        }
                    }
                }
                if (!state.loading && !state.loadFailed) {
                    item {
                        OutlinedTextField(value = state.query, onValueChange = { if (usable()) onSearch(it) },
                            modifier = Modifier.fillMaxWidth().testTag("epub-passage-search"),
                            enabled = filtersEnabled, singleLine = true,
                            label = { Text("Pasaj veya bağlı notta ara") })
                    }
                    if (state.query.isNotEmpty()) item {
                        TextButton(onClick = { if (usable()) onClearSearch() }, enabled = filtersEnabled,
                            modifier = Modifier.testTag("epub-passage-search-clear")) { Text("Temizle") }
                    }
                    item {
                        Column(Modifier.fillMaxWidth()) {
                            TextButton(onClick = { if (usable()) sortExpanded = !sortExpanded }, enabled = filtersEnabled,
                                modifier = Modifier.fillMaxWidth().testTag("epub-passage-sort")) {
                                Text("Sıralama: ${state.sort.label()}", Modifier.fillMaxWidth())
                            }
                            if (sortExpanded) {
                                EpubPassageSort.entries.forEach { option ->
                                    TextButton(enabled = filtersEnabled, modifier = Modifier.fillMaxWidth(),
                                        onClick = { if (usable()) onSort(option); sortExpanded = false }) {
                                        Text(option.label(), Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.pendingDelete == null) state.message?.let { message ->
                    item { Text(message) }
                }
                navigationError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                if (state.busy) item { Text("İşlem sürüyor…", style = MaterialTheme.typography.bodySmall) }
                if (navigationBusy) item { Text("Bölüm doğrulanıyor…", style = MaterialTheme.typography.bodySmall) }
                when {
                    state.loading -> item { CircularProgressIndicator() }
                    state.loadFailed -> {
                        item { Text("Pasajlar yüklenemedi.", color = MaterialTheme.colorScheme.error) }
                        item { TextButton(onClick = { if (live.sessionId == state.sessionId && !live.busy &&
                            live.pendingDelete == null && live.pendingEdit == null && !liveNavigationBusy) onReload() },
                            enabled = !state.busy && !navigationBusy && state.pendingDelete == null && state.pendingEdit == null,
                            modifier = Modifier.fillMaxWidth()) { Text("Yeniden yükle", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) } }
                    }
                    state.entries.isEmpty() -> item { Text("Bu kitap için henüz pasaj kaydedilmedi.") }
                    state.visibleEntries.isEmpty() -> item { Text("Aramanla eşleşen pasaj bulunamadı.") }
                    else -> itemsIndexed(state.visibleEntries, key = { _, entry -> entry.id }) { index, entry -> key(entry) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.text)
                            entry.highlightColor?.let { color -> Text(stringResource(R.string.annotation_color_label, color.colorLabel())) }
                            entry.attachedNote?.let { note ->
                                Text(stringResource(R.string.annotation_attached_note_label), style = MaterialTheme.typography.labelLarge)
                                Text(note)
                            }
                            onEdit?.let { edit -> TextButton(onClick = {
                                if (usable() && live.recoveredNote == null && live.additionalRecoveredEdit == null && live.entries.singleOrNull { it.id == entry.id } == entry) edit(entry)
                            }, enabled = canWrite && filtersEnabled && state.recoveredNote == null && state.additionalRecoveredEdit == null, modifier = Modifier.fillMaxWidth().readerDialogReturnFocusTarget(returnFocus == "edit-${entry.id}") { returnFocus = null }.testTag("epub-annotation-edit-${entry.id}")) {
                                Text(stringResource(R.string.annotation_edit_action), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            } }
                            onCopy?.let { copy ->
                                TextButton(onClick = {
                                    if (usable() && live.entries.singleOrNull { it.id == entry.id } == entry) {
                                        val succeeded = try {
                                            copy(entry.id)
                                            true
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: RuntimeException) {
                                            false
                                        }
                                        copyFeedback = PassageCopyFeedback(entry.id, succeeded)
                                    }
                                }, enabled = canWrite && filtersEnabled,
                                    modifier = Modifier.fillMaxWidth().semantics {
                                        contentDescription = "${index + 1}. pasaj: ${entry.text.replace('\n', ' ').take(40)}, kopyala"
                                    }) { Text("Kopyala", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                            }
                            copyFeedback?.takeIf { it.entryId == entry.id }?.let { feedback ->
                                Text(if (feedback.succeeded) "Pasaj panoya kopyalandı."
                                    else "Pasaj kopyalanamadı. Yeniden deneyebilirsin.",
                                    color = if (feedback.succeeded) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.error)
                            }
                            onOpenChapter?.let { open ->
                                TextButton(onClick = { if (usable() && live.entries.singleOrNull { it.id == entry.id } == entry) open(entry.id) },
                                    enabled = canWrite && filtersEnabled,
                                    modifier = Modifier.fillMaxWidth().semantics {
                                        contentDescription = "${index + 1}. pasaj: ${entry.text.replace('\n', ' ').take(40)}, bölümünü aç"
                                    }) { Text("Bölümünü aç", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                            }
                            TextButton(onClick = { if (usable() && live.entries.singleOrNull { it.id == entry.id } == entry) onRequestDelete(entry.id) },
                                enabled = canWrite && filtersEnabled,
                                modifier = Modifier.fillMaxWidth().readerDialogReturnFocusTarget(returnFocus == "delete-${entry.id}") { returnFocus = null }.testTag("epub-annotation-delete-${entry.id}").semantics {
                                    contentDescription = "${index + 1}. pasaj: ${entry.text.replace('\n', ' ').take(40)}, sil"
                                }) { Text("Sil", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                            HorizontalDivider()
                        }
                    } }
                }
            }
            TextButton(onClick = { if (live.sessionId == state.sessionId && !live.busy && live.pendingDelete == null &&
                live.pendingEdit == null && !liveNavigationBusy) onDismiss() },
                enabled = !state.busy && !navigationBusy && state.pendingDelete == null && state.pendingEdit == null,
                modifier = Modifier.fillMaxWidth().readerDialogReturnFocusTarget(returnFocus == "close") { returnFocus = null }) { Text("Kapat", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        }
      }
    }
    }
    state.pendingDelete?.let { entry ->
        key(state.sessionId, entry) {
          fun current() = live.sessionId == state.sessionId && live.pendingDelete == entry && !live.busy
          Dialog(onDismissRequest = { if (current()) onCancelDelete() },
              properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
              Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(20.dp),
                  verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Pasaj silinsin mi?", style = MaterialTheme.typography.headlineSmall) }
                    item { Text(entry.text) }
                    state.recoveredNote?.let { note -> item {
                        RecoveredPassageNoteContent(note, state.recoveredColor, state.recoveryRevision, state.busy, state.sessionId) { text, revision ->
                            if (current() && live.recoveredNote == text && live.recoveryRevision == revision) onDiscardRecoveredNote(text, revision)
                        }
                    } }
                    state.additionalRecoveredEdit?.let { recovered -> item {
                        RecoveredPassageEditContent(recovered, state.busy, state.sessionId) {
                            if (current() && live.additionalRecoveredEdit === it) onDiscardAdditionalRecoveredEdit(it)
                        }
                    } }
                    if (!canWrite) item { Text(readerUnavailableMessage, color = MaterialTheme.colorScheme.error) }
                    if (state.deleteChanged) item { Text(stringResource(R.string.annotation_changed), color = MaterialTheme.colorScheme.error) }
                    state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
                TextButton(onClick = { if (current()) onCancelDelete() }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) { Text("Vazgeç", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                TextButton(onClick = { if (current() && canWrite && !live.deleteChanged && !live.loading && !live.loadFailed) onDelete() },
                    enabled = canWrite && !state.busy && !state.deleteChanged && !state.loading && !state.loadFailed,
                    modifier = Modifier.fillMaxWidth()) { Text("Pasajı sil", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
              }
            }
          }
        }
    }
}
