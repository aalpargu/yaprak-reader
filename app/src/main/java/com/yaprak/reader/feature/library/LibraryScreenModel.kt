package com.yaprak.reader.feature.library

import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFolderSnapshot
import com.yaprak.reader.domain.library.BookOrganization
import com.yaprak.reader.domain.library.StudyExportFormat

/** What the library shows and how: books, search, sort, filters and layout. */
data class LibraryScreenState(
    val library: LibraryState,
    val query: String,
    val sort: LibrarySort = LibrarySort.RECENT,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val organizationFilters: LibraryOrganizationFilters = LibraryOrganizationFilters(),
    val layout: LibraryLayout = LibraryLayout.GRID,
    val preferenceStatus: LibraryPreferencesStatus = LibraryPreferencesStatus.Ready,
    val bookReadingMillis: Map<String, Long> = emptyMap(),
    val coverLoader: (suspend (Book) -> Bitmap?)? = null,
)

/** Long-running work and its feedback; while any of it runs, book and library actions wait. */
data class LibraryWorkState(
    val importing: Boolean = false,
    val importProgress: ImportProgress? = null,
    val importPickerPending: Boolean = false,
    val importNotice: ImportNotice? = null,
    val exporting: Boolean = false,
    val studyExporting: Boolean = false,
    val removal: BookRemovalState = BookRemovalState.Idle,
    val removalNotice: BookRemovalNotice? = null,
    val metadataEdit: BookMetadataEditState = BookMetadataEditState.Idle,
    val organizationEdit: BookOrganizationEditState = BookOrganizationEditState.Idle,
    val coverEdit: CoverEditState = CoverEditState.Idle,
    val backupAvailable: Boolean = false,
    val backupRunning: BackupOperation? = null,
    val backupPickerPending: Boolean = false,
    val discovery: DiscoveryState = DiscoveryState.Idle,
    val bookFolder: BookFolderState = BookFolderState(),
) {
    val backupBusy get() = backupRunning != null || backupPickerPending
    val importBusy get() = importing || importPickerPending
    val exportBusy get() = exporting || studyExporting
    val metadataBusy get() = metadataEdit is BookMetadataEditState.Saving || organizationEdit is BookOrganizationEditState.Saving
    val coverBusy get() = coverEdit is CoverEditState.Selecting || coverEdit is CoverEditState.Changing ||
        coverEdit is CoverEditState.Resetting
    val discoveryBusy get() = discovery != DiscoveryState.Idle
    /** Any running work that must finish before another book or library change starts. */
    val mutationBusy get() = importBusy || exportBusy || backupBusy || metadataBusy || coverBusy || discoveryBusy ||
        bookFolder.busy || removal is BookRemovalState.Removing
}

/** Browsing the shelf: open, add, search, sort, filter and view choices. */
data class LibraryBrowseActions(
    val onImport: () -> Unit,
    val onRetry: () -> Unit,
    val onRead: (String) -> Unit,
    val onSearch: (String) -> Unit,
    val onSort: (LibrarySort) -> Unit = {},
    val onFilter: (LibraryFilter) -> Unit = {},
    val onOrganizationFilters: (LibraryOrganizationFilters) -> Unit = {},
    val onResetFilters: (() -> Unit)? = null,
    val onLayout: (LibraryLayout) -> Unit = {},
    val onRetryPreferences: () -> Unit = {},
    val onStopRemainingImports: (() -> Unit)? = null,
    val onDismissImportNotice: () -> Unit = {},
)

/** One book's details window: export, removal, metadata, organization and cover. */
data class LibraryBookActions(
    val onExport: (Book) -> Unit = {},
    val onRemove: (Book) -> Unit = {},
    val onClearRemovalError: (String) -> Unit = {},
    val onSaveMetadata: (Book, String, String?) -> Unit = { _, _, _ -> },
    val onClearMetadataState: (String) -> Unit = {},
    val onStudyExport: ((Book) -> Unit)? = null,
    val onStudyExportFormat: ((Book, StudyExportFormat) -> Unit)? = null,
    val onSaveOrganization: (Book, BookOrganization) -> Unit = { _, _ -> },
    val onClearOrganizationState: (String, String) -> Unit = { _, _ -> },
    val onChangeCover: ((Book) -> Unit)? = null,
    val onResetCover: ((Book) -> Unit)? = null,
    val onClearCoverEditState: (String, String) -> Unit = { _, _ -> },
)

/** The "Kitaplık araçları" window: other screens and backup. */
data class LibraryToolActions(
    val onAllNotes: (() -> Unit)? = null,
    val onTextSearch: (() -> Unit)? = null,
    val onStatistics: (() -> Unit)? = null,
    val onWords: (() -> Unit)? = null,
    val onLabels: (() -> Unit)? = null,
    val onBackupCreate: () -> Unit = {},
    val onBackupRestore: () -> Unit = {},
)

/** Finding books in a folder, and the remembered folder. */
data class LibraryDiscoveryActions(
    val onDiscover: (() -> Unit)? = null,
    val onSearch: (String, String) -> Unit = { _, _ -> },
    val onSelect: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val onSelectAll: (String) -> Unit = {},
    val onClearSelection: (String) -> Unit = {},
    val onImport: (String) -> Unit = {},
    val onDismiss: (String) -> Unit = {},
    val onCancel: (String) -> Unit = {},
    val onRememberFolder: ((String, String, BookFolderSnapshot) -> Unit)? = null,
    val onSavedFolderScan: (BookFolderSnapshot) -> Unit = {},
    val onForgetFolder: (BookFolderSnapshot) -> Unit = {},
    val onReloadFolder: () -> Unit = {},
    val onCancelFolderScan: () -> Unit = {},
    val onFolderAutomatic: (BookFolderSnapshot, Boolean) -> Unit = { _, _ -> },
    val onFolderOpeningUi: (Boolean, Boolean) -> Unit = { _, _ -> },
)

/**
 * Flat-argument form kept for the existing UI tests. It only packs its arguments into the groups
 * above; the screen itself is [LibraryScreen] with grouped state and callbacks.
 */
@Composable
internal fun LibraryScreen(
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
    onRememberFolder: ((String, String, BookFolderSnapshot) -> Unit)? = null,
    onSavedFolderScan: (BookFolderSnapshot) -> Unit = {},
    onForgetFolder: (BookFolderSnapshot) -> Unit = {},
    onReloadFolder: () -> Unit = {}, onCancelFolderScan: () -> Unit = {},
    onFolderAutomatic: (BookFolderSnapshot, Boolean) -> Unit = { _, _ -> },
    onFolderOpeningUi: (Boolean, Boolean) -> Unit = { _, _ -> },
    bookReadingMillis: Map<String, Long> = emptyMap(),
) = LibraryScreen(
    screen = LibraryScreenState(state, query, sort, filter, organizationFilters, layout, preferenceStatus,
        bookReadingMillis, coverLoader),
    work = LibraryWorkState(importing, importProgress, importPickerPending, importNotice, exporting, studyExporting,
        removal, removalNotice, metadataEdit, organizationEdit, coverEdit, backupAvailable, backupRunning,
        backupPickerPending, discoveryState, bookFolderState),
    snackbarHostState = snackbarHostState,
    browse = LibraryBrowseActions(onImport, onRetry, onRead, onSearch, onSort, onFilter, onOrganizationFilters,
        onResetFilters, onLayout, onRetryPreferences, onStopRemainingImports, onDismissImportNotice),
    books = LibraryBookActions(onExport, onRemove, onClearRemovalError, onSaveMetadata, onClearMetadataState,
        onStudyExport, onStudyExportFormat, onSaveOrganization, onClearOrganizationState, onChangeCover, onResetCover,
        onClearCoverEditState),
    tools = LibraryToolActions(onAllNotes, onTextSearch, onStatistics, onWords, onLabels, onBackupCreate, onBackupRestore),
    discovery = LibraryDiscoveryActions(onDiscover, onDiscoverySearch, onDiscoverySelect, onDiscoverySelectAll,
        onDiscoveryClearSelection, onDiscoveryImport, onDiscoveryDismiss, onDiscoveryCancel, onRememberFolder,
        onSavedFolderScan, onForgetFolder, onReloadFolder, onCancelFolderScan, onFolderAutomatic, onFolderOpeningUi),
)
