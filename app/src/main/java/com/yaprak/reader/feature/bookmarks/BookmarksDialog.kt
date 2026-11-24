package com.yaprak.reader.feature.bookmarks

import android.os.Bundle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.SideEffect
import com.yaprak.reader.feature.reader.rememberReaderPanelAdmission
import com.yaprak.reader.feature.reader.ReaderDraftSnapshot
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.yaprak.reader.feature.reader.readerDialogReturnFocusTarget
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yaprak.reader.domain.bookmarks.BookmarkRepository
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.reader.BookmarkNavigator
import com.yaprak.reader.R
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException

@Composable
internal fun BookmarksRoute(book: Book, repository: BookmarkRepository, navigator: BookmarkNavigator,
    onNavigate: (ReadingLocation, () -> Boolean) -> Boolean, onDismiss: () -> Unit,
    restoration: Bundle? = null, onRestorationChange: (Bundle?) -> Unit = {}, canMutate: () -> Boolean = { true }, canInteract: () -> Boolean = canMutate, presentationEpoch: Long = 0L) {
    val model: BookmarksViewModel = viewModel(key = "bookmarks-${book.id}-${book.sha256}", factory = viewModelFactory {
        initializer { BookmarksViewModel(book.id, book.sha256, repository) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    var navigationCommitted by remember(model) { mutableStateOf(false) }
    val admitted = rememberReaderPanelAdmission("$presentationEpoch:" + (state.pendingRename?.let { "edit-${it.id}" }
        ?: state.pendingDelete?.let { "delete-${it.id}" }), canInteract)
    fun fresh() = !navigationCommitted && admitted() && canMutate()
    val restored = remember(model) { ReaderDraftSnapshot.fromBundle(restoration) }
    var restorationApplied by remember(model) { mutableStateOf(false) }
    val baseListState = rememberLazyListState(restored?.listIndex ?: 0, restored?.listOffset ?: 0)
    LaunchedEffect(model, state.loading, state.loadFailed, state.busy) {
        if (!state.loading && !state.loadFailed && !state.busy && !restorationApplied) {
            model.restorePresentation(restored)
            restorationApplied = true
        }
    }
    val snapshotChanged by rememberUpdatedState(onRestorationChange)
    fun publishSnapshot() {
        if (!restorationApplied) return
        snapshotChanged(model.snapshot(baseListState.firstVisibleItemIndex, baseListState.firstVisibleItemScrollOffset).toBundle())
    }
    SideEffect { publishSnapshot() }
    LaunchedEffect(baseListState) {
        snapshotFlow { baseListState.firstVisibleItemIndex to baseListState.firstVisibleItemScrollOffset }
            .collect { publishSnapshot() }
    }

    LaunchedEffect(Unit) { model.refreshPosition(navigator.capture()) }
    BookmarksDialog(state, { if (fresh()) { model.add(); publishSnapshot() } }, { if (admitted()) { model.reload(); publishSnapshot() } }, { if (fresh()) { model.requestDelete(it); publishSnapshot() } }, { if (fresh()) model.delete() }, { if (admitted()) { model.cancelDelete(); publishSnapshot() } },
        { location ->
            val expected = model.state.value
            fresh() && !expected.loading && !expected.loadFailed && !expected.busy &&
                expected.pendingRename == null && expected.pendingDelete == null &&
                expected.visibleEntries.any { it.target.location == location } && try { onNavigate(location) {
                if (!fresh() || model.state.value !== expected || expected.busy) false
                else {
                    navigationCommitted = true
                    model.clearSession()
                    onDismiss()
                    true
                }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        }, { if (!navigationCommitted && admitted() && !state.busy) { model.clearSession(); onDismiss() } }, { if (fresh()) { model.requestRename(it); publishSnapshot() } }, { if (admitted()) { model.editRename(it); publishSnapshot() } },
        { if (fresh()) { model.saveRename(); publishSnapshot() } }, { if (admitted()) { model.cancelRename(); publishSnapshot() } }, { if (admitted()) { model.search(it); publishSnapshot() } }, { if (admitted()) { model.clearSearch(); publishSnapshot() } }, { if (admitted()) { model.setSort(it); publishSnapshot() } }, baseListState, canWrite = canMutate(),
        onDiscardRecoveredRename = { if (admitted()) { model.discardRecoveredRename(it); publishSnapshot() } })
}

@Composable
internal fun BookmarksDialog(state: BookmarksState, onAdd: () -> Unit, onReload: () -> Unit,
    onRequestDelete: (String) -> Unit, onDelete: () -> Unit, onCancelDelete: () -> Unit,
    onNavigate: (ReadingLocation) -> Boolean, onDismiss: () -> Unit,
    onRequestRename: (String) -> Unit, onEditRename: (String) -> Unit,
    onSaveRename: () -> Unit, onCancelRename: () -> Unit,
    onSearch: (String) -> Unit = {}, onClearSearch: () -> Unit = {},
    onSort: (BookmarkSort) -> Unit = {},
    baseListState: LazyListState = rememberLazyListState(), canWrite: Boolean = true,
    onDiscardRecoveredRename: (RecoveredBookmarkRename) -> Unit = {}) {
    var navigationFailed by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    // Keep the caller and scroll position while only the child owns an input Window.
    var suspendedChild by remember { mutableStateOf<String?>(null) }
    var returnFocus by remember { mutableStateOf<String?>(null) }
    val child = state.pendingRename?.let { "edit-${it.id}" }
        ?: state.pendingDelete?.let { "delete-${it.id}" }
    LaunchedEffect(child) {
        if (child != null) {
            suspendedChild = child
            returnFocus = null
            sortExpanded = false
        } else {
            suspendedChild?.let { caller ->
                val id = caller.substringAfter('-')
                returnFocus = if (canWrite && !state.loading && !state.loadFailed &&
                    state.visibleEntries.any { it.id == id }) caller else "close"
            }
            suspendedChild = null
        }
    }
    if (state.pendingRename == null && state.pendingDelete == null) AlertDialog(modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss, title = { Column { Text("Yer imleri"); if (!canWrite) Text("Okuyucu henüz hazır değil. Bu pencereyi kapatıp hata bildiriminden yeniden deneyebilirsin.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }, text = {
        LazyColumn(state = baseListState, modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).testTag("bookmarks-list"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.recoveredRename?.let { recovered -> item { RecoveredBookmarkRenameContent(recovered, state.busy, onDiscardRecoveredRename) } }
            state.additionalRecoveredRename?.let { recovered -> item { RecoveredBookmarkRenameContent(recovered, state.busy, onDiscardRecoveredRename, secondary = true) } }
            item { Button(onClick = onAdd, enabled = canWrite && state.canAdd) { Text("Bu konumu kaydet") } }
            if (state.target == null) item { Text("Konum henüz hazır değil. Okuma ekranına dönüp yeniden dene.") }
            else if (!state.loading && state.entries.any { it.target.location == state.target.location }) item { Text("Bu konum zaten kayıtlı.") }
            if (!state.loading && !state.loadFailed) {
                item {
                    OutlinedTextField(value = state.query, onValueChange = onSearch,
                        modifier = Modifier.fillMaxWidth().testTag("bookmark-search"),
                        enabled = !state.busy && state.pendingRename == null && state.pendingDelete == null,
                        label = { Text("Yer imi başlığında ara") }, singleLine = true)
                }
                if (state.query.isNotEmpty()) item {
                    TextButton(onClick = onClearSearch,
                        enabled = !state.busy && state.pendingRename == null && state.pendingDelete == null) { Text("Temizle") }
                }
                item {
                    Box {
                        TextButton(onClick = { sortExpanded = true },
                            enabled = !state.busy && state.pendingRename == null && state.pendingDelete == null,
                            modifier = Modifier.testTag("bookmark-sort")) {
                            Text("Sıralama: ${state.sort.label()}")
                        }
                        DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                            BookmarkSort.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label()) }, onClick = {
                                    onSort(option)
                                    sortExpanded = false
                                })
                            }
                        }
                    }
                }
            }
            if (state.pendingRename == null) state.message?.let { message ->
                item { Text(message, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.busy) item { Text("İşlem sürüyor…", style = MaterialTheme.typography.bodySmall) }
            if (navigationFailed) item { Text("Kayıtlı konuma gidilemedi.", color = MaterialTheme.colorScheme.error) }
            when {
                state.loading -> item { CircularProgressIndicator() }
                state.loadFailed -> {
                    item { Text("Yer imleri yüklenemedi.", color = MaterialTheme.colorScheme.error) }
                    item { TextButton(onClick = onReload, enabled = !state.busy) { Text("Yeniden yükle") } }
                }
                state.entries.isEmpty() -> item { Text("Bu kitapta henüz yer imi yok.") }
                state.visibleEntries.isEmpty() -> item { Text("Aramanla eşleşen yer imi bulunamadı.") }
                else -> itemsIndexed(state.visibleEntries, key = { _, entry -> entry.id }) { index, entry ->
                        val navigateDescription = stringResource(R.string.bookmark_navigate_accessibility, index + 1, entry.target.label)
                        val deleteDescription = stringResource(R.string.bookmark_delete_accessibility, index + 1, entry.target.label)
                        val renameDescription = stringResource(R.string.bookmark_rename_accessibility, index + 1, entry.target.label)
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            TextButton(onClick = { if (onNavigate(entry.target.location)) onDismiss() else navigationFailed = true },
                                enabled = canWrite && !state.busy, modifier = Modifier.semantics { contentDescription = navigateDescription }) {
                                Text(entry.target.label)
                            }
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.createdAt)), style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { onRequestRename(entry.id) }, enabled = canWrite && !state.busy && state.recoveredRename == null && state.additionalRecoveredRename == null,
                                modifier = Modifier.testTag("bookmark-rename-${entry.id}")
                                    .readerDialogReturnFocusTarget(returnFocus == "edit-${entry.id}") { returnFocus = null }
                                    .semantics { contentDescription = renameDescription }) { Text("Düzenle") }
                            TextButton(onClick = { onRequestDelete(entry.id) }, enabled = canWrite && !state.busy,
                                modifier = Modifier.testTag("bookmark-delete-${entry.id}")
                                    .readerDialogReturnFocusTarget(returnFocus == "delete-${entry.id}") { returnFocus = null }
                                    .semantics { contentDescription = deleteDescription }) { Text("Sil") }
                        }
                        HorizontalDivider()
                    }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss,
            modifier = Modifier.testTag("bookmark-close")
                .readerDialogReturnFocusTarget(returnFocus == "close") { returnFocus = null }, enabled = !state.busy) { Text("Kapat") } })
    if (state.pendingRename != null) {
        AlertDialog(modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = onCancelRename, title = { Column { Text("Yer imi başlığını düzenle"); if (!canWrite) Text("Okuyucu henüz hazır değil. Bu pencereyi kapatıp hata bildiriminden yeniden deneyebilirsin.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.recoveredRename?.let { RecoveredBookmarkRenameContent(it, state.busy, onDiscardRecoveredRename) }
                    state.additionalRecoveredRename?.let { RecoveredBookmarkRenameContent(it, state.busy, onDiscardRecoveredRename, secondary = true) }
                    OutlinedTextField(value = state.renameDraft, onValueChange = onEditRename,
                        modifier = Modifier.fillMaxWidth().testTag("bookmark-rename-field"), enabled = !state.busy, singleLine = true,
                        label = { Text("Yer imi başlığı") },
                        isError = state.renameDraft.trim().length !in 1..200,
                        supportingText = { Text("Başlık 1–200 karakter olmalı.") })
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.busy) Text("İşlem sürüyor…", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = onSaveRename, enabled = canWrite && state.canRename) { Text("Kaydet") } },
            dismissButton = { TextButton(onClick = onCancelRename, enabled = !state.busy) { Text("Vazgeç") } })
    }
    state.pendingDelete?.takeIf { state.pendingRename == null }?.let { entry ->
        AlertDialog(onDismissRequest = onCancelDelete, title = { Column { Text("Yer imi silinsin mi?"); if (!canWrite) Text("Okuyucu henüz hazır değil. Bu pencereyi kapatıp hata bildiriminden yeniden deneyebilirsin.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } },
            text = { Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                state.recoveredRename?.let { RecoveredBookmarkRenameContent(it, state.busy, onDiscardRecoveredRename) }
                state.additionalRecoveredRename?.let { RecoveredBookmarkRenameContent(it, state.busy, onDiscardRecoveredRename, secondary = true) }
                Text(entry.target.label); state.message?.let { Text(it) }
            } },
            confirmButton = { TextButton(onClick = onDelete, enabled = canWrite && !state.busy) { Text("Yer imini sil") } },
            dismissButton = { TextButton(onClick = onCancelDelete, enabled = !state.busy) { Text("Vazgeç") } })
    }
}

@Composable
private fun RecoveredBookmarkRenameContent(recovered: RecoveredBookmarkRename, busy: Boolean,
    onDiscard: (RecoveredBookmarkRename) -> Unit, secondary: Boolean = false) {
    key(recovered.id) {
        val tag = if (secondary) "bookmark-additional-recovered" else "bookmark-recovered"
        val admitted = rememberReaderPanelAdmission(recovered.id) { true }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (secondary) "Diğer korunan yer imi başlığı" else "Korunan yer imi başlığı", style = MaterialTheme.typography.titleSmall)
            Text("Başka bir başlık düzenlemeden önce bu taslağı kopyalayıp sil.", style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(recovered.label, Modifier.testTag("$tag-label"))
            }
            TextButton(onClick = { if (admitted() && !busy) onDiscard(recovered) }, enabled = !busy,
                modifier = Modifier.testTag("$tag-discard")) { Text(if (secondary) "Diğer korunan taslağı sil" else "Korunan taslağı sil") }
        }
    }
}
