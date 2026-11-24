package com.yaprak.reader.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.domain.library.ImportResult
import java.util.UUID

internal data class StudyFormatSelection(val book: Book, val id: String = UUID.randomUUID().toString())

/**
 * Every window the library opens over the shelf: study-export format, book details, organization
 * filters, the batch-import summary and folder discovery. Callbacks re-check the latest state at
 * click time, so a window composed for an older book or a busy library cannot act.
 */
@Composable
internal fun LibraryDialogs(
    screen: LibraryScreenState,
    work: LibraryWorkState,
    snackbarHostState: SnackbarHostState,
    browse: LibraryBrowseActions,
    books: LibraryBookActions,
    discovery: LibraryDiscoveryActions,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    filterDialogOpen: Boolean,
    onFilterDialog: (Boolean) -> Unit,
    studyFormatSelection: StudyFormatSelection?,
    onStudyFormatSelection: (StudyFormatSelection?) -> Unit,
    resetFilters: () -> Unit,
) {
    val state = screen.library
    val liveStudySelection by rememberUpdatedState(studyFormatSelection)
    val liveStudyFormatCallback by rememberUpdatedState(books.onStudyExportFormat)
    val liveSelectedId by rememberUpdatedState(selectedId)
    val liveState by rememberUpdatedState(state)
    val liveMutationBusy by rememberUpdatedState(work.mutationBusy)
    val liveFilterDialogOpen by rememberUpdatedState(filterDialogOpen)
    studyFormatSelection?.let { selection -> key(selection.id) {
        val current = (state as? LibraryState.Ready)?.allBooks?.singleOrNull { it.id == selection.book.id }
        val changed = current != selection.book
        StudyExportFormatDialog(selection.book, enabled = !liveMutationBusy && !changed,
            changed = changed, onSelect = { format ->
                val latest = (liveState as? LibraryState.Ready)?.allBooks?.singleOrNull { it.id == selection.book.id }
                if (liveStudySelection?.id == selection.id && !liveMutationBusy && latest == selection.book &&
                    liveStudyFormatCallback != null) {
                    onStudyFormatSelection(null)
                    liveStudyFormatCallback?.invoke(selection.book, format)
                }
            }, onDismiss = { if (liveStudySelection?.id == selection.id) onStudyFormatSelection(null) })
    } }
    // Keep the selected book visible when a successful organization save changes its filter match.
    (state as? LibraryState.Ready)?.allBooks?.firstOrNull { it.id == selectedId }?.let { book ->
        val coverCallbackAllowed = {
            val current = (liveState as? LibraryState.Ready)?.allBooks?.singleOrNull { it.id == liveSelectedId }
            !liveMutationBusy && current != null && current.id == book.id && current.sha256 == book.sha256 &&
                current.customCover?.id == book.customCover?.id
        }
        BookDetailsDialog(book,
            progressPercent = (state as? LibraryState.Ready)?.progress?.get(book.id),
            readingMillis = screen.bookReadingMillis[book.id],
            onRead = { if (coverCallbackAllowed()) { onSelect(null); browse.onRead(book.id) } },
            onExport = { if (coverCallbackAllowed()) { onSelect(null); books.onExport(book) } },
            onStudyExport = if (books.onStudyExportFormat != null) ({
                val current = (liveState as? LibraryState.Ready)?.allBooks?.singleOrNull { it.id == liveSelectedId }
                if (coverCallbackAllowed() && current == book && liveStudyFormatCallback != null) {
                    onSelect(null)
                    onStudyFormatSelection(StudyFormatSelection(book))
                }
            }) else books.onStudyExport?.let { export -> {
                if (coverCallbackAllowed()) { onSelect(null); export(book) }
            } },
            studyExportFormatsAvailable = books.onStudyExportFormat != null,
            exportEnabled = !liveMutationBusy,
            readEnabled = !liveMutationBusy,
            onDismiss = {
                onSelect(null); books.onClearRemovalError(book.id)
                books.onClearMetadataState(book.id); books.onClearOrganizationState(book.id, book.sha256)
                books.onClearCoverEditState(book.id, book.sha256)
            },
            removeEnabled = !liveMutationBusy,
            removalState = work.removal,
            onRemove = { books.onRemove(book) },
            onClearRemovalError = { books.onClearRemovalError(book.id) },
            metadataEdit = work.metadataEdit,
            editEnabled = !liveMutationBusy,
            onSaveMetadata = books.onSaveMetadata,
            onClearMetadataState = books.onClearMetadataState,
            organizationEdit = work.organizationEdit,
            onSaveOrganization = { requested, organization ->
                val current = (liveState as? LibraryState.Ready)?.allBooks?.singleOrNull { it.id == liveSelectedId }
                if (current?.id == requested.id && current?.sha256 == requested.sha256)
                    books.onSaveOrganization(requested, organization)
            },
            onClearOrganizationState = books.onClearOrganizationState,
            coverLoader = screen.coverLoader,
            coverEdit = work.coverEdit,
            coverEditEnabled = !liveMutationBusy,
            onChangeCover = books.onChangeCover?.let { change -> { if (coverCallbackAllowed()) change(book) } },
            onResetCover = books.onResetCover?.let { reset -> { if (coverCallbackAllowed()) reset(book) } })
    }
    if (filterDialogOpen) LibraryOrganizationFiltersDialog(
        books = (state as? LibraryState.Ready)?.allBooks.orEmpty(), filters = screen.organizationFilters,
        onApply = { filters ->
            if (filterDialogOpen && liveFilterDialogOpen) { browse.onOrganizationFilters(filters); onFilterDialog(false) }
        },
        onDismiss = { onFilterDialog(false) },
        onResetAll = { if (filterDialogOpen && liveFilterDialogOpen) { resetFilters(); onFilterDialog(false) } },
    )
    // A fully successful batch needs no decision from the user: a snackbar is enough.
    val importNotice = work.importNotice
    val cleanBatch = importNotice?.takeIf { notice -> notice.isBatch && notice.stopped == 0 && notice.skipped == 0 &&
        notice.results.isNotEmpty() && notice.results.all { it == ImportResult.Added } }
    LaunchedEffect(cleanBatch?.id) {
        // Dismissing first would change this effect's key and cancel the snackbar.
        if (cleanBatch != null) {
            try { snackbarHostState.showSnackbar("${cleanBatch.results.size} kitap eklendi") }
            finally { browse.onDismissImportNotice() }
        }
    }
    if (!work.discoveryBusy && cleanBatch == null) importNotice?.takeIf { it.isBatch }?.let { BatchImportDialog(it, browse.onDismissImportNotice) }
    BookDiscoveryDialog(work.discovery, discovery.onSearch, discovery.onSelect, discovery.onSelectAll,
        discovery.onClearSelection, discovery.onImport, discovery.onDismiss, discovery.onCancel, work.bookFolder,
        discovery.onRememberFolder)
}

@Composable
private fun BatchImportDialog(notice: ImportNotice, onDismiss: () -> Unit) {
    val added = notice.results.count { it == ImportResult.Added }
    val duplicate = notice.results.count { it == ImportResult.Duplicate }
    val failures = notice.results.filterIsInstance<ImportResult.Failed>()
        .groupingBy { it.reason }.eachCount()
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.import_summary_title),
                    style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.import_summary_added, added))
                Text(stringResource(R.string.import_summary_duplicate, duplicate))
                Text(stringResource(R.string.import_summary_failed, failures.values.sum()))
                if (notice.stopped > 0) Text(stringResource(R.string.import_summary_stopped, notice.stopped))
                if (notice.skipped > 0) Text(stringResource(R.string.import_summary_skipped,
                    notice.skipped, MAX_IMPORT_SELECTION))
                ImportFailure.entries.forEach { reason ->
                    failures[reason]?.let { count ->
                        Text(stringResource(R.string.import_summary_failure_reason,
                            count, stringResource(reason.messageResource())))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}
