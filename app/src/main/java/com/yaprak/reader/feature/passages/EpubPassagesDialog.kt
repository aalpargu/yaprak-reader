package com.yaprak.reader.feature.passages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.passages.EpubPassage
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
    onOpenChapter: (suspend (EpubPassage) -> Boolean)? = null,
    onCopy: ((text: String) -> Unit)? = null,
    canMutate: () -> Boolean = { true },
) {
    if (book.format != BookFormat.EPUB) return
    val model: EpubPassagesViewModel = viewModel(key = "epub-passages-${book.id}-${book.sha256}", factory = viewModelFactory {
        initializer { EpubPassagesViewModel(book.id, book.sha256, repository) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sessionId = remember(model) { UUID.randomUUID().toString() }
    val liveAllowed by rememberUpdatedState(canMutate)
    val liveDismiss by rememberUpdatedState(onDismiss)
    fun fresh() = model.state.value.sessionId == sessionId && liveAllowed()
    var openingChapterId by remember(book.id, book.sha256) { mutableStateOf<String?>(null) }
    var navigationError by remember(book.id, book.sha256) { mutableStateOf<String?>(null) }
    // ReaderActivity recreates its dialog-open flag on rotation; do not leak an old draft
    // into a later, deliberate opening of the retained Activity-scoped ViewModel.
    DisposableEffect(model, sessionId) {
        model.startSession(sessionId)
        onDispose { model.clearSession(sessionId) }
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
                scope.launch {
                    val accepted = try { navigate(entry) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                    if (!fresh()) return@launch
                    // A changed row invalidates the result, but must still release this session's
                    // navigation gate so its refreshed records and close action remain usable.
                    if (model.state.value.entries.singleOrNull { it.id == id } != entry) {
                        openingChapterId = null
                        navigationError = stringChangedRecord
                        return@launch
                    }
                    if (accepted) {
                        model.clearSession()
                        liveDismiss()
                    } else {
                        openingChapterId = null
                        navigationError = "Bölüm doğrulanamadı veya açılamadı. Pasajın tam yeri gösterilemez."
                    }
                }
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
    EpubPassagesDialog(state, { if (fresh()) model.save() }, { if (fresh()) model.reload() },
        { if (fresh()) model.requestDelete(it) }, { if (fresh()) model.delete() },
        { if (fresh()) model.cancelDelete() }, {
            val current = model.state.value
            if (fresh() && !current.busy && current.pendingDelete == null && current.pendingEdit == null && openingChapterId == null) {
                model.clearSession()
                liveDismiss()
            }
        }, openChapter, openingChapterId, navigationError, copyPassage,
        { if (fresh()) model.search(it) }, { if (fresh()) model.clearSearch() }, { if (fresh()) model.setSort(it) },
        onEdit = { if (fresh()) model.requestEdit(it) })
    state.pendingEdit?.let { expected ->
        EpubAnnotationDialog(state.sessionId, expected.text, state.editColor, state.editNote, state.busy,
            state.canUpdate && canMutate(), state.editRevision, failed = state.message != null,
            changed = state.editChanged, editing = true,
            onColor = { if (fresh()) model.editColor(it) }, onNote = { if (fresh()) model.editNote(it) },
            onSave = { if (fresh()) model.update() }, onDismiss = { if (fresh()) model.cancelEdit() })
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
) {
    val live by rememberUpdatedState(state)
    val liveNavigationBusy by rememberUpdatedState(openingChapterId != null)
    val navigationBusy = openingChapterId != null
    val filtersEnabled = !state.loading && !state.loadFailed && !state.busy &&
        state.pendingDelete == null && state.pendingEdit == null && !navigationBusy
    var sortExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val sortItemIndex = 2 + (if (state.draft != null) 1 else 0) + 1 + (if (state.query.isNotEmpty()) 1 else 0)
    LaunchedEffect(filtersEnabled) { if (!filtersEnabled) sortExpanded = false }
    LaunchedEffect(sortExpanded, sortItemIndex) {
        if (sortExpanded) listState.scrollToItem(sortItemIndex)
    }
    var copyFeedback by remember(state.entries) { mutableStateOf<PassageCopyFeedback?>(null) }
    key(state.sessionId) {
    fun usable() = live.sessionId == state.sessionId && !live.loading && !live.loadFailed && !live.busy &&
        live.pendingDelete == null && live.pendingEdit == null && !liveNavigationBusy
    Dialog(onDismissRequest = { if (usable()) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
          shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("epub-passages-list"), state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.annotations_title), style = MaterialTheme.typography.headlineSmall) }
                item { Text(stringResource(R.string.annotations_list_help)) }
                state.draft?.let { draft ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Kaydedilmemiş seçim", style = MaterialTheme.typography.titleSmall)
                            Text(draft.text, modifier = Modifier.testTag("epub-passage-draft"))
                            key(draft) {
                                Button(onClick = { if (usable() && live.draft == draft && live.canSave) onSave() }, enabled = state.canSave && !navigationBusy,
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
                                if (usable() && live.entries.singleOrNull { it.id == entry.id } == entry) edit(entry)
                            }, enabled = filtersEnabled, modifier = Modifier.fillMaxWidth().testTag("epub-annotation-edit-${entry.id}")) {
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
                                }, enabled = filtersEnabled,
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
                                    enabled = filtersEnabled,
                                    modifier = Modifier.fillMaxWidth().semantics {
                                        contentDescription = "${index + 1}. pasaj: ${entry.text.replace('\n', ' ').take(40)}, bölümünü aç"
                                    }) { Text("Bölümünü aç", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                            }
                            TextButton(onClick = { if (usable() && live.entries.singleOrNull { it.id == entry.id } == entry) onRequestDelete(entry.id) },
                                enabled = filtersEnabled,
                                modifier = Modifier.fillMaxWidth().semantics {
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
                modifier = Modifier.fillMaxWidth()) { Text("Kapat", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
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
                    if (state.deleteChanged) item { Text(stringResource(R.string.annotation_changed), color = MaterialTheme.colorScheme.error) }
                    state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
                TextButton(onClick = { if (current()) onCancelDelete() }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) { Text("Vazgeç", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                TextButton(onClick = { if (current() && !live.deleteChanged && !live.loading && !live.loadFailed) onDelete() },
                    enabled = !state.busy && !state.deleteChanged && !state.loading && !state.loadFailed,
                    modifier = Modifier.fillMaxWidth()) { Text("Pasajı sil", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
              }
            }
          }
        }
    }
}
