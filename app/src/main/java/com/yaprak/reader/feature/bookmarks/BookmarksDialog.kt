package com.yaprak.reader.feature.bookmarks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@Composable
internal fun BookmarksRoute(book: Book, repository: BookmarkRepository, navigator: BookmarkNavigator,
    onNavigate: (ReadingLocation) -> Boolean, onDismiss: () -> Unit) {
    val model: BookmarksViewModel = viewModel(key = "bookmarks-${book.id}-${book.sha256}", factory = viewModelFactory {
        initializer { BookmarksViewModel(book.id, book.sha256, repository) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.selectPosition(navigator.capture()) }
    BookmarksDialog(state, model::add, model::reload, model::requestDelete, model::delete, model::cancelDelete,
        onNavigate, { if (!state.busy) { model.clearSession(); onDismiss() } }, model::requestRename, model::editRename,
        model::saveRename, model::cancelRename, model::search, model::clearSearch, model::setSort)
}

@Composable
internal fun BookmarksDialog(state: BookmarksState, onAdd: () -> Unit, onReload: () -> Unit,
    onRequestDelete: (String) -> Unit, onDelete: () -> Unit, onCancelDelete: () -> Unit,
    onNavigate: (ReadingLocation) -> Boolean, onDismiss: () -> Unit,
    onRequestRename: (String) -> Unit, onEditRename: (String) -> Unit,
    onSaveRename: () -> Unit, onCancelRename: () -> Unit,
    onSearch: (String) -> Unit = {}, onClearSearch: () -> Unit = {},
    onSort: (BookmarkSort) -> Unit = {}) {
    var navigationFailed by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    AlertDialog(modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss, title = { Text("Yer imleri") }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).testTag("bookmarks-list"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Button(onClick = onAdd, enabled = state.canAdd) { Text("Bu konumu kaydet") } }
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
                                enabled = !state.busy, modifier = Modifier.semantics { contentDescription = navigateDescription }) {
                                Text(entry.target.label)
                            }
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.createdAt)), style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { onRequestRename(entry.id) }, enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = renameDescription }) { Text("Düzenle") }
                            TextButton(onClick = { onRequestDelete(entry.id) }, enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = deleteDescription }) { Text("Sil") }
                        }
                        HorizontalDivider()
                    }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Kapat") } })
    if (state.pendingRename != null) {
        AlertDialog(modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = onCancelRename, title = { Text("Yer imi başlığını düzenle") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = state.renameDraft, onValueChange = onEditRename,
                        modifier = Modifier.fillMaxWidth().testTag("bookmark-rename-field"), enabled = !state.busy, singleLine = true,
                        label = { Text("Yer imi başlığı") },
                        isError = state.renameDraft.trim().length !in 1..200,
                        supportingText = { Text("Başlık 1–200 karakter olmalı.") })
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.busy) Text("İşlem sürüyor…", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = onSaveRename, enabled = state.canRename) { Text("Kaydet") } },
            dismissButton = { TextButton(onClick = onCancelRename, enabled = !state.busy) { Text("Vazgeç") } })
    }
    state.pendingDelete?.takeIf { state.pendingRename == null }?.let { entry ->
        AlertDialog(onDismissRequest = onCancelDelete, title = { Text("Yer imi silinsin mi?") },
            text = { Column { Text(entry.target.label); state.message?.let { Text(it) } } },
            confirmButton = { TextButton(onClick = onDelete, enabled = !state.busy) { Text("Yer imini sil") } },
            dismissButton = { TextButton(onClick = onCancelDelete, enabled = !state.busy) { Text("Vazgeç") } })
    }
}
