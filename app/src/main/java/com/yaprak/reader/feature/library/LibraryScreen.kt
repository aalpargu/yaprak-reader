package com.yaprak.reader.feature.library

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookOrganization
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.domain.library.ImportResult
import com.yaprak.reader.domain.library.StudyExportFormat
import java.util.UUID

private data class StudyFormatSelection(val book: Book, val id: String = UUID.randomUUID().toString())

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun LibraryScreen(
    state: LibraryState,
    importing: Boolean,
    snackbarHostState: SnackbarHostState,
    onImport: () -> Unit,
    onRetry: () -> Unit,
    onRead: (String) -> Unit,
    query: String,
    onSearch: (String) -> Unit,
    exporting: Boolean = false,
    onExport: (Book) -> Unit = {},
    removal: BookRemovalState = BookRemovalState.Idle,
    removalNotice: BookRemovalNotice? = null,
    onRemove: (Book) -> Unit = {},
    onClearRemovalError: (String) -> Unit = {},
    sort: LibrarySort = LibrarySort.RECENT,
    onSort: (LibrarySort) -> Unit = {},
    backupAvailable: Boolean = false,
    backupRunning: BackupOperation? = null,
    backupPickerPending: Boolean = false,
    onBackupCreate: () -> Unit = {},
    onBackupRestore: () -> Unit = {},
    importProgress: ImportProgress? = null,
    importPickerPending: Boolean = false,
    importNotice: ImportNotice? = null,
    onDismissImportNotice: () -> Unit = {},
    filter: LibraryFilter = LibraryFilter.ALL,
    onFilter: (LibraryFilter) -> Unit = {},
    layout: LibraryLayout = LibraryLayout.GRID,
    onLayout: (LibraryLayout) -> Unit = {},
    metadataEdit: BookMetadataEditState = BookMetadataEditState.Idle,
    onSaveMetadata: (Book, String, String?) -> Unit = { _, _, _ -> },
    onClearMetadataState: (String) -> Unit = {},
    coverLoader: (suspend (Book) -> Bitmap?)? = null,
    preferenceStatus: LibraryPreferencesStatus = LibraryPreferencesStatus.Ready,
    onRetryPreferences: () -> Unit = {},
    onStudyExport: ((Book) -> Unit)? = null,
    studyExporting: Boolean = false,
    onStopRemainingImports: (() -> Unit)? = null,
    organizationFilters: LibraryOrganizationFilters = LibraryOrganizationFilters(),
    onOrganizationFilters: (LibraryOrganizationFilters) -> Unit = {},
    onResetFilters: (() -> Unit)? = null,
    organizationEdit: BookOrganizationEditState = BookOrganizationEditState.Idle,
    onSaveOrganization: (Book, BookOrganization) -> Unit = { _, _ -> },
    onClearOrganizationState: (String, String) -> Unit = { _, _ -> },
    coverEdit: CoverEditState = CoverEditState.Idle,
    onChangeCover: ((Book) -> Unit)? = null,
    onResetCover: ((Book) -> Unit)? = null,
    onClearCoverEditState: (String, String) -> Unit = { _, _ -> },
    discoveryState: DiscoveryState = DiscoveryState.Idle,
    onDiscover: (() -> Unit)? = null,
    onDiscoverySearch: (String, String) -> Unit = { _, _ -> },
    onDiscoverySelect: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onDiscoverySelectAll: (String) -> Unit = {},
    onDiscoveryClearSelection: (String) -> Unit = {},
    onDiscoveryImport: (String) -> Unit = {},
    onDiscoveryDismiss: (String) -> Unit = {},
    onDiscoveryCancel: (String) -> Unit = {},
    onAllNotes: (() -> Unit)? = null,
    onStudyExportFormat: ((Book, StudyExportFormat) -> Unit)? = null,
    onTextSearch: (() -> Unit)? = null,
    onStatistics: (() -> Unit)? = null,
    onWords: (() -> Unit)? = null,
    onLabels: (() -> Unit)? = null,
    bookFolderState: BookFolderState = BookFolderState(),
    onRememberFolder: ((String, String, com.yaprak.reader.domain.library.BookFolderSnapshot) -> Unit)? = null,
    onSavedFolderScan: (com.yaprak.reader.domain.library.BookFolderSnapshot) -> Unit = {},
    onForgetFolder: (com.yaprak.reader.domain.library.BookFolderSnapshot) -> Unit = {},
    onReloadFolder: () -> Unit = {}, onCancelFolderScan: () -> Unit = {},
    onFolderAutomatic: (com.yaprak.reader.domain.library.BookFolderSnapshot, Boolean) -> Unit = { _, _ -> },
    onFolderOpeningUi: (Boolean, Boolean) -> Unit = { _, _ -> },
) {
    val focus = LocalFocusManager.current
    val backupBusy = backupRunning != null || backupPickerPending
    val importBusy = importing || importPickerPending
    val exportBusy = exporting || studyExporting
    val metadataBusy = metadataEdit is BookMetadataEditState.Saving ||
        organizationEdit is BookOrganizationEditState.Saving
    val coverBusy = coverEdit is CoverEditState.Selecting || coverEdit is CoverEditState.Changing ||
        coverEdit is CoverEditState.Resetting
    val discoveryBusy = discoveryState != DiscoveryState.Idle
    val preferencesEnabled = preferenceStatus != LibraryPreferencesStatus.Loading &&
        preferenceStatus != LibraryPreferencesStatus.LoadFailed
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var filterDialogOpen by remember { mutableStateOf(false) }
    var studyFormatSelection by remember { mutableStateOf<StudyFormatSelection?>(null) }
    val liveStudySelection by rememberUpdatedState(studyFormatSelection)
    val liveStudyFormatCallback by rememberUpdatedState(onStudyExportFormat)
    val liveSelectedId by rememberUpdatedState(selectedId)
    val liveState by rememberUpdatedState(state)
    val liveMutationBusy by rememberUpdatedState(importBusy || exportBusy || backupBusy || metadataBusy ||
        coverBusy || discoveryBusy || bookFolderState.busy || removal is BookRemovalState.Removing)
    val liveFilterDialogOpen by rememberUpdatedState(filterDialogOpen)
    val liveAllNotes by rememberUpdatedState(onAllNotes)
    val liveTextSearch by rememberUpdatedState(onTextSearch)
    val liveStatistics by rememberUpdatedState(onStatistics)
    val liveWords by rememberUpdatedState(onWords)
    val liveLabels by rememberUpdatedState(onLabels)
    androidx.compose.runtime.SideEffect {
        if (bookFolderState.available) onFolderOpeningUi(state !is LibraryState.Loading,
            state is LibraryState.Ready && !liveMutationBusy && selectedId == null && !filterDialogOpen &&
                studyFormatSelection == null && importNotice?.isBatch != true)
    }
    val resetFilters = {
        if (onResetFilters != null) onResetFilters() else {
            onFilter(LibraryFilter.ALL)
            onOrganizationFilters(LibraryOrganizationFilters())
            onSearch("")
        }
    }
    LaunchedEffect(removalNotice?.id) {
        if (removalNotice != null) selectedId = null
    }
    LaunchedEffect(backupBusy) { if (backupBusy) selectedId = null }
    LaunchedEffect(importBusy) { if (importBusy) selectedId = null }
    LaunchedEffect(discoveryBusy) {
        if (discoveryBusy) { selectedId = null; filterDialogOpen = false }
    }
    val books = (state as? LibraryState.Ready)?.books.orEmpty()
    val continueBook = if (query.isBlank() && filter == LibraryFilter.ALL && !organizationFilters.isActive)
        mostRecentlyReadBook(books) else null
    studyFormatSelection?.let { selection -> key(selection.id) {
        val current = (state as? LibraryState.Ready)?.allBooks?.singleOrNull { it.id == selection.book.id }
        val changed = current != selection.book
        StudyExportFormatDialog(selection.book, enabled = !liveMutationBusy && !changed,
            changed = changed, onSelect = { format ->
                val latest = (liveState as? LibraryState.Ready)?.allBooks?.singleOrNull { it.id == selection.book.id }
                if (liveStudySelection?.id == selection.id && !liveMutationBusy && latest == selection.book &&
                    liveStudyFormatCallback != null) {
                    studyFormatSelection = null
                    liveStudyFormatCallback?.invoke(selection.book, format)
                }
            }, onDismiss = { if (liveStudySelection?.id == selection.id) studyFormatSelection = null })
    } }
    // Keep the selected book visible when a successful organization save changes its filter match.
    (state as? LibraryState.Ready)?.allBooks?.firstOrNull { it.id == selectedId }?.let { book ->
        val coverCallbackAllowed = {
            val current = (liveState as? LibraryState.Ready)?.allBooks?.singleOrNull { it.id == liveSelectedId }
            !liveMutationBusy && current != null && current.id == book.id && current.sha256 == book.sha256 &&
                current.customCover?.id == book.customCover?.id
        }
        BookDetailsDialog(book,
            onRead = { if (coverCallbackAllowed()) { selectedId = null; onRead(book.id) } },
            onExport = { if (coverCallbackAllowed()) { selectedId = null; onExport(book) } },
            onStudyExport = if (onStudyExportFormat != null) ({
                val current = (liveState as? LibraryState.Ready)?.allBooks?.singleOrNull { it.id == liveSelectedId }
                if (coverCallbackAllowed() && current == book && liveStudyFormatCallback != null) {
                    selectedId = null
                    studyFormatSelection = StudyFormatSelection(book)
                }
            }) else onStudyExport?.let { export -> {
                if (coverCallbackAllowed()) { selectedId = null; export(book) }
            } },
            studyExportFormatsAvailable = onStudyExportFormat != null,
            exportEnabled = !liveMutationBusy,
            readEnabled = !liveMutationBusy,
            onDismiss = {
                selectedId = null; onClearRemovalError(book.id)
                onClearMetadataState(book.id); onClearOrganizationState(book.id, book.sha256)
                onClearCoverEditState(book.id, book.sha256)
            },
            removeEnabled = !liveMutationBusy,
            removalState = removal,
            onRemove = { onRemove(book) },
            onClearRemovalError = { onClearRemovalError(book.id) },
            metadataEdit = metadataEdit,
            editEnabled = !liveMutationBusy,
            onSaveMetadata = onSaveMetadata,
            onClearMetadataState = onClearMetadataState,
            organizationEdit = organizationEdit,
            onSaveOrganization = { requested, organization ->
                val current = (liveState as? LibraryState.Ready)?.allBooks?.singleOrNull { it.id == liveSelectedId }
                if (current?.id == requested.id && current?.sha256 == requested.sha256)
                    onSaveOrganization(requested, organization)
            },
            onClearOrganizationState = onClearOrganizationState,
            coverLoader = coverLoader,
            coverEdit = coverEdit,
            coverEditEnabled = !liveMutationBusy,
            onChangeCover = onChangeCover?.let { change -> { if (coverCallbackAllowed()) change(book) } },
            onResetCover = onResetCover?.let { reset -> { if (coverCallbackAllowed()) reset(book) } })
    }
    if (filterDialogOpen) LibraryOrganizationFiltersDialog(
        books = (state as? LibraryState.Ready)?.allBooks.orEmpty(), filters = organizationFilters,
        onApply = { filters ->
            if (filterDialogOpen && liveFilterDialogOpen) { onOrganizationFilters(filters); filterDialogOpen = false }
        },
        onDismiss = { filterDialogOpen = false },
        onResetAll = { if (filterDialogOpen && liveFilterDialogOpen) { resetFilters(); filterDialogOpen = false } },
    )
    if (!discoveryBusy) importNotice?.takeIf { it.isBatch }?.let { BatchImportDialog(it, onDismissImportNotice) }
    BookDiscoveryDialog(discoveryState, onDiscoverySearch, onDiscoverySelect, onDiscoverySelectAll,
        onDiscoveryClearSelection, onDiscoveryImport, onDiscoveryDismiss, onDiscoveryCancel, bookFolderState, onRememberFolder)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state is LibraryState.Ready && state.totalCount > 0 && !liveMutationBusy) {
                ExtendedFloatingActionButton(onClick = onImport, icon = { AddIcon() }, text = { Text(stringResource(R.string.add_book)) })
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = if (layout == LibraryLayout.GRID) GridCells.Adaptive(148.dp) else GridCells.Fixed(1),
                modifier = Modifier.widthIn(max = 900.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 112.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { LibraryHeader() }
                onLabels?.let { labels -> item(key = "labels", span = { GridItemSpan(maxLineSpan) }) {
                    key(labels) {
                        TextButton(onClick = {
                            if (liveLabels === labels && liveState is LibraryState.Ready && !liveMutationBusy &&
                                liveSelectedId == null && !liveFilterDialogOpen && liveStudySelection == null) labels()
                        }, enabled = state is LibraryState.Ready && !liveMutationBusy && selectedId == null &&
                            !filterDialogOpen && studyFormatSelection == null, modifier = Modifier.fillMaxWidth()) {
                            Text("Etiket ve koleksiyonlar", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                } }
                onWords?.let { words -> item(key = "vocabulary", span = { GridItemSpan(maxLineSpan) }) {
                    key(words) {
                        TextButton(onClick = {
                            if (liveWords === words && liveState is LibraryState.Ready && !liveMutationBusy &&
                                liveSelectedId == null && !liveFilterDialogOpen && liveStudySelection == null) words()
                        }, enabled = state is LibraryState.Ready && !liveMutationBusy && selectedId == null &&
                            !filterDialogOpen && studyFormatSelection == null, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.library_words_title), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                } }
                onStatistics?.let { statistics -> item(key = "statistics", span = { GridItemSpan(maxLineSpan) }) {
                    key(statistics) {
                        TextButton(onClick = {
                            if (liveStatistics === statistics && liveState is LibraryState.Ready && !liveMutationBusy &&
                                liveSelectedId == null && !liveFilterDialogOpen && liveStudySelection == null) statistics()
                        }, enabled = state is LibraryState.Ready && !liveMutationBusy && selectedId == null &&
                            !filterDialogOpen && studyFormatSelection == null, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.reading_statistics_title), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                } }
                onTextSearch?.let { textSearch -> item(key = "textSearch", span = { GridItemSpan(maxLineSpan) }) {
                    key(textSearch) {
                        TextButton(onClick = {
                            if (liveTextSearch === textSearch && liveState is LibraryState.Ready && !liveMutationBusy &&
                                liveSelectedId == null && !liveFilterDialogOpen && liveStudySelection == null) textSearch()
                        }, enabled = state is LibraryState.Ready && !liveMutationBusy && selectedId == null &&
                            !filterDialogOpen && studyFormatSelection == null, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.library_text_title), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                } }
                onAllNotes?.let { allNotes -> item(key = "allNotes", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { if (liveAllNotes != null && liveState is LibraryState.Ready && !liveMutationBusy) allNotes() },
                        enabled = state is LibraryState.Ready && !liveMutationBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.library_notes_title), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                } }
                onDiscover?.let { discover -> item(key = "folderDiscovery", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { if (!liveMutationBusy) discover() },
                            enabled = state is LibraryState.Ready && !liveMutationBusy) {
                            Text(stringResource(R.string.discovery_open_folder))
                        }
                        if (discoveryState is DiscoveryState.Choosing) Text(stringResource(R.string.discovery_selecting))
                        else Text(stringResource(R.string.discovery_folder_help), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } }
                if (bookFolderState.available) item(key = "savedFolder", span = { GridItemSpan(maxLineSpan) }) {
                    BookFolderPanel(bookFolderState, state is LibraryState.Ready && !liveMutationBusy && selectedId == null &&
                        !filterDialogOpen && studyFormatSelection == null, onSavedFolderScan, onForgetFolder, onReloadFolder, onCancelFolderScan,
                        onFolderAutomatic)
                }
                if (preferenceStatus == LibraryPreferencesStatus.LoadFailed ||
                    preferenceStatus == LibraryPreferencesStatus.SaveFailed ||
                    (preferenceStatus == LibraryPreferencesStatus.Loading &&
                        state is LibraryState.Ready && state.totalCount > 0)) {
                    item(key = "libraryPreferencesStatus", span = { GridItemSpan(maxLineSpan) }) {
                        LibraryPreferencesFeedback(preferenceStatus, onRetryPreferences)
                    }
                }
                if (backupAvailable) item(span = { GridItemSpan(maxLineSpan) }) {
                    val actionsEnabled = state is LibraryState.Ready && !liveMutationBusy
                    LibraryBackupPanel(
                        createEnabled = actionsEnabled,
                        restoreEnabled = actionsEnabled && (state as? LibraryState.Ready)?.totalCount == 0,
                        running = backupRunning,
                        pickerPending = backupPickerPending,
                        onCreate = onBackupCreate,
                        onRestore = onBackupRestore,
                    )
                }
                continueBook?.let { book ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.continue_reading_title),
                                    style = MaterialTheme.typography.titleMedium)
                                Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { if (!liveMutationBusy) onRead(book.id) },
                                    enabled = !liveMutationBusy) {
                                    Text(stringResource(R.string.continue_reading_action))
                                }
                            }
                        }
                    }
                }
                if (importing) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.importing), style = MaterialTheme.typography.titleMedium)
                                Text(if (importProgress != null && importProgress.total > 1)
                                    stringResource(R.string.importing_batch_progress,
                                        importProgress.completed, importProgress.total)
                                    else stringResource(R.string.importing_body),
                                    style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (importProgress != null && importProgress.total > 1 &&
                                    importProgress.completed < importProgress.total && onStopRemainingImports != null) {
                                    TextButton(onClick = onStopRemainingImports,
                                        enabled = !importProgress.stopRequested) {
                                        Text(stringResource(R.string.import_stop_remaining))
                                    }
                                    if (importProgress.stopRequested) {
                                        Text(stringResource(R.string.import_stop_waiting),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                if (exportBusy) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(if (studyExporting) R.string.exporting_study_records
                                    else R.string.exporting_book), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(if (studyExporting) R.string.exporting_study_records_body
                                    else R.string.exporting_book_body), style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                when (state) {
                    LibraryState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    LibraryState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.library_error))
                            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                        }
                    }
                    is LibraryState.Ready -> {
                        if (state.totalCount == 0) {
                            item(span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary(liveMutationBusy, onImport) }
                        } else {
                            item(key = "librarySearchControls", span = { GridItemSpan(maxLineSpan) }) {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    OutlinedTextField(value = query, onValueChange = onSearch,
                                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                                        label = { Text(stringResource(R.string.search_books)) },
                                        trailingIcon = if (query.isNotEmpty()) ({
                                            TextButton(onClick = { onSearch(""); focus.clearFocus() }) { Text(stringResource(R.string.clear_search)) }
                                        }) else null,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    LibraryFilters(filter, onFilter)
                                    TextButton(onClick = { filterDialogOpen = true }) {
                                        Text(if (organizationFilters.isActive)
                                            stringResource(R.string.library_filters_active, organizationFilters.activeCount)
                                            else stringResource(R.string.library_filters))
                                    }
                                    LibraryLayoutPicker(layout, onLayout, preferencesEnabled)
                                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalArrangement = Arrangement.Center) {
                                        Text(if (state.query.isBlank() && filter == LibraryFilter.ALL && !organizationFilters.isActive)
                                            stringResource(R.string.library_count, state.totalCount)
                                            else stringResource(R.string.search_count, books.size, state.totalCount), style = MaterialTheme.typography.labelSmall)
                                        LibrarySortMenu(sort, onSort, preferencesEnabled)
                                    }
                                }
                            }
                            if (books.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(if (filter == LibraryFilter.ALL && !organizationFilters.isActive) R.string.no_search_results
                                        else R.string.no_filter_results), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(if (filter == LibraryFilter.ALL && !organizationFilters.isActive) R.string.no_search_results_body
                                        else R.string.no_filter_results_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (filter != LibraryFilter.ALL || organizationFilters.isActive || query.isNotBlank())
                                        TextButton(onClick = resetFilters) { Text(stringResource(R.string.show_all_books)) }
                                }
                            }
                            items(books, key = { it.id }) { book -> BookCard(book,
                                onClick = {
                                    if (!liveMutationBusy) selectedId = book.id
                                }, compact = layout == LibraryLayout.LIST, coverLoader = coverLoader)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LibraryFilters(filter: LibraryFilter, onFilter: (LibraryFilter) -> Unit) {
    val labels = mapOf(
        LibraryFilter.ALL to stringResource(R.string.filter_all),
        LibraryFilter.EPUB to stringResource(R.string.filter_epub),
        LibraryFilter.PDF to stringResource(R.string.filter_pdf),
        LibraryFilter.TXT to stringResource(R.string.filter_txt),
        LibraryFilter.CBZ to stringResource(R.string.filter_cbz),
        LibraryFilter.CBR to "CBR",
        LibraryFilter.FB2 to "FB2",
        LibraryFilter.DOCX to "DOCX",
        LibraryFilter.NO_POSITION to stringResource(R.string.filter_no_position),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LibraryFilter.entries.forEach { option ->
            val label = labels.getValue(option)
            val accessibility = stringResource(R.string.filter_accessibility, label)
            FilterChip(selected = filter == option, onClick = { onFilter(option) },
                label = { Text(label) },
                modifier = Modifier.semantics { contentDescription = accessibility })
        }
    }
    if (filter == LibraryFilter.DOCX) Text(stringResource(R.string.docx_format_scope),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LibraryLayoutPicker(
    layout: LibraryLayout,
    onLayout: (LibraryLayout) -> Unit,
    enabled: Boolean,
) {
    val labels = mapOf(
        LibraryLayout.GRID to stringResource(R.string.library_layout_grid),
        LibraryLayout.LIST to stringResource(R.string.library_layout_list),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryLayout.entries.forEach { option ->
            val label = labels.getValue(option)
            val accessibility = stringResource(R.string.library_layout_accessibility, label)
            FilterChip(selected = layout == option, onClick = { onLayout(option) },
                enabled = enabled,
                label = { Text(label) },
                modifier = Modifier.semantics { contentDescription = accessibility })
        }
    }
}

@Composable
private fun LibraryPreferencesFeedback(
    status: LibraryPreferencesStatus,
    onRetry: () -> Unit,
) {
    if (status == LibraryPreferencesStatus.Loading) {
        Text(stringResource(R.string.library_preferences_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val retryAccessibility = stringResource(R.string.library_preferences_retry_accessibility)
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (status == LibraryPreferencesStatus.LoadFailed)
                    R.string.library_preferences_load_error else R.string.library_preferences_save_error))
                TextButton(onClick = onRetry,
                    modifier = Modifier.semantics { contentDescription = retryAccessibility }) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
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

@Composable
private fun LibrarySortMenu(sort: LibrarySort, onSort: (LibrarySort) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    val labels = mapOf(
        LibrarySort.RECENT to stringResource(R.string.sort_recent),
        LibrarySort.LAST_READ to stringResource(R.string.sort_last_read),
        LibrarySort.TITLE to stringResource(R.string.sort_title),
        LibrarySort.AUTHOR to stringResource(R.string.sort_author),
    )
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(stringResource(R.string.sort_button, labels.getValue(sort)))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            LibrarySort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labels.getValue(option)) },
                    onClick = { expanded = false; onSort(option) },
                    enabled = enabled,
                    modifier = Modifier.semantics { selected = option == sort },
                )
            }
        }
    }
}

@Composable
private fun LibraryHeader() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary) {
                Icon(painterResource(R.drawable.ic_book), contentDescription = null, modifier = Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.app_name), fontFamily = FontFamily.Serif, fontSize = 23.sp)
                Text(stringResource(R.string.brand_caption), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(36.dp))
        Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.library_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EmptyLibrary(importing: Boolean, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(168.dp, 156.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(112.dp, 138.dp).rotate(-12f).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)))
            Surface(Modifier.size(108.dp, 140.dp).rotate(7f), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), shadowElevation = 3.dp) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_book), contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(30.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onImport, enabled = !importing, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 15.dp)) {
            AddIcon()
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.add_book))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.formats_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(38.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.local_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddIcon() { Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(22.dp)) }
