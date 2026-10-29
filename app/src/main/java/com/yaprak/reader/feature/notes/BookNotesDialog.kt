package com.yaprak.reader.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.notes.BookNoteRepository
import com.yaprak.reader.domain.notes.MAX_BOOK_NOTE_LENGTH
import kotlinx.coroutines.CancellationException

@Composable
internal fun BookNotesRoute(
    book: Book,
    repository: BookNoteRepository,
    onDismiss: () -> Unit,
    prefillText: String? = null,
    capturePdfPage: (() -> Int?)? = null,
    onGoToPdfPage: ((Int) -> Boolean)? = null,
    onCopy: ((text: String) -> Unit)? = null,
) {
    val model: BookNotesViewModel = viewModel(key = "book-notes-${book.id}-${book.sha256}", factory = viewModelFactory {
        initializer { BookNotesViewModel(book.id, book.sha256, repository) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    var navigationMessage by remember(book.id, book.sha256) { mutableStateOf<String?>(null) }
    LaunchedEffect(prefillText, model) {
        prefillText?.let(model::prefillDraft)
    }
    val copyNote: ((String) -> Unit)? = onCopy?.let { copyText ->
        { id: String ->
            val current = model.state.value
            val entry = current.entries.singleOrNull { it.id == id }
            check(entry != null && !current.loading && !current.loadFailed && !current.busy &&
                current.pendingEdit == null && current.pendingDelete == null) {
                "Note is unavailable for copying"
            }
            copyText(entry.text)
        }
    }
    BookNotesDialog(
        state = state,
        onEditDraft = model::editDraft,
        onSave = model::add,
        onSaveAtPdfPage = if (book.format == BookFormat.PDF && capturePdfPage != null) {
            { model.addAtPdfPage(capturePdfPage) }
        } else null,
        onReload = model::reload,
        onRequestDelete = model::requestDelete,
        onDelete = model::delete,
        onCancelDelete = model::cancelDelete,
        onDismiss = {
            if (!state.busy && state.pendingDelete == null && state.pendingEdit == null) {
                model.clearSession()
                onDismiss()
            }
        },
        onRequestEdit = model::requestEdit,
        onEditSelectedDraft = model::editSelectedDraft,
        onSaveEdit = model::saveEdit,
        onCancelEdit = model::cancelEdit,
        onSearch = model::search,
        onClearSearch = model::clearSearch,
        onSort = model::setSort,
        onGoToPdfPage = if (book.format == BookFormat.PDF) onGoToPdfPage?.let { navigate ->
            { pageIndex: Int ->
                if (!state.busy && state.pendingDelete == null && state.pendingEdit == null) {
                    val accepted = try { navigate(pageIndex) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                    if (accepted) {
                        model.clearSession()
                        onDismiss()
                    } else navigationMessage = "PDF sayfasına gidilemedi. Yeniden deneyebilirsin."
                }
            }
        } else null,
        navigationMessage = navigationMessage,
        onCopy = copyNote,
    )
}

private data class NoteCopyFeedback(val entryId: String, val succeeded: Boolean)

@Composable
internal fun BookNotesDialog(
    state: BookNotesState,
    onEditDraft: (String) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDismiss: () -> Unit,
    onRequestEdit: (String) -> Unit = {},
    onEditSelectedDraft: (String) -> Unit = {},
    onSaveEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onSearch: (String) -> Unit = {},
    onClearSearch: () -> Unit = {},
    onSort: (BookNoteSort) -> Unit = {},
    onSaveAtPdfPage: (() -> Unit)? = null,
    onGoToPdfPage: ((Int) -> Unit)? = null,
    navigationMessage: String? = null,
    onCopy: ((noteId: String) -> Unit)? = null,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var copyFeedback by remember(state.entries) { mutableStateOf<NoteCopyFeedback?>(null) }
    val copyEnabled = !state.loading && !state.loadFailed && !state.busy &&
        state.pendingEdit == null && state.pendingDelete == null
    AlertDialog(
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text("Kitap notları") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).testTag("book-notes-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(if (onSaveAtPdfPage == null) "Bu not kitabın geneline aittir; okuduğun konuma bağlanmaz."
                    else "Notu kitabın geneline veya o anda görüntülenen PDF sayfasına kaydedebilirsin.") }
                item {
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = onEditDraft,
                        modifier = Modifier.fillMaxWidth().testTag("book-note-draft"),
                        enabled = !state.busy && state.pendingEdit == null && state.pendingDelete == null,
                        label = { Text("Yeni not") },
                        minLines = 3,
                        maxLines = 8,
                        isError = state.draft.isNotEmpty() && state.draft.trim().length !in 1..MAX_BOOK_NOTE_LENGTH,
                        supportingText = { Text("1–2000 karakter") },
                    )
                }
                item { Button(onClick = onSave, enabled = state.canSave) { Text("Notu kaydet") } }
                if (onSaveAtPdfPage != null) item {
                    Button(onClick = onSaveAtPdfPage, enabled = state.canSave,
                        modifier = Modifier.testTag("book-note-save-at-page")) {
                        Text("Bu PDF sayfasına not kaydet")
                    }
                }
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onSearch,
                        modifier = Modifier.fillMaxWidth().testTag("book-note-search"),
                        enabled = !state.busy && state.pendingEdit == null && state.pendingDelete == null,
                        label = { Text("Not metninde ara") },
                        singleLine = true,
                    )
                }
                if (state.query.isNotEmpty()) {
                    item { TextButton(onClick = onClearSearch,
                        enabled = !state.busy && state.pendingEdit == null && state.pendingDelete == null) {
                        Text("Temizle")
                    } }
                }
                item {
                    Box {
                        TextButton(
                            onClick = { sortExpanded = true },
                            enabled = !state.busy && state.pendingEdit == null && state.pendingDelete == null,
                            modifier = Modifier.testTag("book-note-sort"),
                        ) { Text("Sıralama: ${state.sort.label()}") }
                        DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                            BookNoteSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label()) },
                                    onClick = {
                                        onSort(option)
                                        sortExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (state.pendingDelete == null && state.pendingEdit == null) {
                    state.message?.let { message -> item { Text(message) } }
                    navigationMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
                if (state.busy) item { Text("İşlem sürüyor…", style = MaterialTheme.typography.bodySmall) }
                when {
                    state.loading -> item { CircularProgressIndicator() }
                    state.loadFailed -> {
                        item { Text("Notlar yüklenemedi.", color = MaterialTheme.colorScheme.error) }
                        item { TextButton(onClick = onReload, enabled = !state.busy) { Text("Yeniden yükle") } }
                    }
                    state.entries.isEmpty() -> item { Text("Bu kitap için henüz not kaydedilmedi.") }
                    state.visibleEntries.isEmpty() -> item { Text("Aramanla eşleşen not bulunamadı.") }
                    else -> itemsIndexed(state.visibleEntries, key = { _, entry -> entry.id }) { index, entry ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.text)
                            onCopy?.let { copy ->
                                TextButton(onClick = {
                                    if (copyEnabled) {
                                        val succeeded = try {
                                            copy(entry.id)
                                            true
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: RuntimeException) {
                                            false
                                        }
                                        copyFeedback = NoteCopyFeedback(entry.id, succeeded)
                                    }
                                }, enabled = copyEnabled,
                                    modifier = Modifier.testTag("book-note-copy-${entry.id}").semantics {
                                        contentDescription = "${index + 1}. not: ${entry.text.replace('\n', ' ').take(40)}, kopyala"
                                    }) { Text("Kopyala") }
                            }
                            copyFeedback?.takeIf { it.entryId == entry.id }?.let { feedback ->
                                Text(if (feedback.succeeded) "Not panoya kopyalandı."
                                    else "Not kopyalanamadı. Yeniden deneyebilirsin.",
                                    color = if (feedback.succeeded) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.error)
                            }
                            entry.pdfPageIndex?.let { pageIndex ->
                                Text("PDF sayfası ${pageIndex.toLong() + 1}", style = MaterialTheme.typography.bodySmall)
                                if (onGoToPdfPage != null && pageIndex >= 0) TextButton(
                                    onClick = { onGoToPdfPage(pageIndex) },
                                    enabled = !state.busy && state.pendingDelete == null && state.pendingEdit == null,
                                    modifier = Modifier.semantics {
                                        contentDescription = "${index + 1}. not: PDF sayfası ${pageIndex.toLong() + 1}, sayfaya git"
                                    },
                                ) { Text("Sayfaya git") }
                            }
                            TextButton(
                                onClick = { onRequestEdit(entry.id) },
                                enabled = !state.busy && state.pendingDelete == null && state.pendingEdit == null,
                                modifier = Modifier.semantics {
                                    contentDescription = "${index + 1}. not: ${entry.text.replace('\n', ' ').take(40)}, düzenle"
                                },
                            ) { Text("Düzenle") }
                            TextButton(
                                onClick = { onRequestDelete(entry.id) },
                                enabled = !state.busy && state.pendingDelete == null && state.pendingEdit == null,
                                modifier = Modifier.semantics {
                                    contentDescription = "${index + 1}. not: ${entry.text.replace('\n', ' ').take(40)}, sil"
                                },
                            ) { Text("Sil") }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss,
            enabled = !state.busy && state.pendingDelete == null && state.pendingEdit == null) {
            Text("Kapat")
        } },
    )
    state.pendingDelete?.let { entry ->
        AlertDialog(
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = onCancelDelete,
            title = { Text("Not silinsin mi?") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(entry.text) }
                    state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
            },
            confirmButton = { TextButton(onClick = onDelete, enabled = !state.busy) { Text("Notu sil") } },
            dismissButton = { TextButton(onClick = onCancelDelete, enabled = !state.busy) { Text("Vazgeç") } },
        )
    }
    state.pendingEdit?.let { entry ->
        AlertDialog(
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = onCancelEdit,
            title = { Text("Notu düzenle") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp).testTag("book-note-edit-content"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(if (entry.pdfPageIndex == null) "Bu not kitabın geneline aittir; okuduğun konuma bağlanmaz."
                        else "Bu not PDF sayfası ${entry.pdfPageIndex.toLong() + 1} ile bağlıdır; düzenleme sayfayı değiştirmez.") }
                    item {
                        OutlinedTextField(
                            value = state.editDraft,
                            onValueChange = onEditSelectedDraft,
                            modifier = Modifier.fillMaxWidth().testTag("book-note-edit-draft"),
                            enabled = !state.busy,
                            label = { Text("Not metni") },
                            minLines = 3,
                            maxLines = 8,
                            isError = state.editDraft.trim().length !in 1..MAX_BOOK_NOTE_LENGTH,
                            supportingText = { Text("1–2000 karakter") },
                        )
                    }
                    state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                    if (state.busy) item { Text("İşlem sürüyor…", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = onSaveEdit, enabled = state.canUpdate) { Text("Kaydet") } },
            dismissButton = { TextButton(onClick = onCancelEdit, enabled = !state.busy) { Text("Vazgeç") } },
        )
    }
}
