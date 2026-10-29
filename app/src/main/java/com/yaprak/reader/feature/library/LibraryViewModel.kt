package com.yaprak.reader.feature.library

import android.graphics.Bitmap
import com.yaprak.reader.covers.BookCoverLoader
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookCoverEditor
import com.yaprak.reader.domain.library.BookDiscovery
import com.yaprak.reader.domain.library.BookOrganization
import com.yaprak.reader.domain.library.BookExporter
import com.yaprak.reader.domain.library.BookStudyExporter
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookImporter
import com.yaprak.reader.domain.library.ExportFailure
import com.yaprak.reader.domain.library.ExportResult
import com.yaprak.reader.domain.library.ImportResult
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.domain.library.LibraryPreferencesRepository
import com.yaprak.reader.domain.library.BookRemover
import com.yaprak.reader.domain.library.BookRemovalResult
import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.backup.BackupResult
import com.yaprak.reader.domain.backup.LibraryBackup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LibraryState {
    data object Loading : LibraryState
    data object Error : LibraryState
    data class Ready(val books: List<Book>, val totalCount: Int = books.size, val query: String = "",
        val allBooks: List<Book> = books) : LibraryState
}
data class ImportProgress(val completed: Int, val total: Int, val stopRequested: Boolean = false)
data class ImportNotice(val id: Long, val results: List<ImportResult>, val skipped: Int, val stopped: Int = 0) {
    val isBatch: Boolean get() = stopped > 0 || results.size + skipped + stopped > 1
}
internal const val MAX_IMPORT_SELECTION = 100
data class ExportNotice(val id: Long, val result: ExportResult)
data class BookExportRequest(val format: BookFormat, val suggestedFileName: String, val fb2Zip: Boolean = false)
sealed interface BookRemovalState {
    data object Idle : BookRemovalState
    data class Removing(val bookId: String) : BookRemovalState
    data class Error(val bookId: String, val result: BookRemovalResult) : BookRemovalState
}
data class BookRemovalNotice(val id: Long, val result: BookRemovalResult.Removed)
sealed interface BookMetadataEditState {
    data object Idle : BookMetadataEditState
    data class Saving(val bookId: String) : BookMetadataEditState
    data class Failed(val bookId: String) : BookMetadataEditState
    data class Saved(val bookId: String, val eventId: Long) : BookMetadataEditState
}

sealed interface BookOrganizationEditState {
    data object Idle : BookOrganizationEditState
    data class Saving(val bookId: String, val sha256: String) : BookOrganizationEditState
    data class Failed(val bookId: String, val sha256: String) : BookOrganizationEditState
    data class Saved(val bookId: String, val sha256: String, val eventId: Long) : BookOrganizationEditState
}
enum class BackupOperation { CREATE, RESTORE }
data class BackupNotice(val id: Long, val result: BackupResult)

/** The name is only a suggestion to the document provider; its extension follows verified metadata. */
internal fun suggestedExportFileName(book: Book): String {
    val clean = book.originalName
        .map { char ->
            if (char.isISOControl() || char.isSurrogate() || char in "\\/:*?\"<>|" || Character.getType(char) == Character.FORMAT.toInt()) '_'
            else char
        }
        .joinToString("")
        .trim().trim('.', '_')
        .take(100).trimEnd(' ', '.', '_')
    val stem = clean.replace(Regex("(?i)\\.(fb2\\.zip|epub|pdf|txt|cbz|cbr|fb2|docx)$"), "").trimEnd(' ', '.', '_')
    return "${stem.ifBlank { "Yaprak-kitap" }}.${book.originalExtension}"
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val repository: LibraryRepository, private val importer: BookImporter,
    private val savedState: SavedStateHandle,
    searchDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val exporter: BookExporter? = null,
    private val remover: BookRemover? = null,
    private val backup: LibraryBackup? = null,
    private val coverLoader: BookCoverLoader? = null,
    private val libraryPreferences: LibraryPreferencesRepository? = null,
    private val studyExporter: BookStudyExporter? = null,
    private val coverEditor: BookCoverEditor? = null,
    private val discovery: BookDiscovery? = null,
    bookFolders: com.yaprak.reader.domain.library.BookFolderRepository? = null,
) : ViewModel() {
    val coversAvailable: Boolean get() = coverLoader != null
    suspend fun loadCover(book: Book): Bitmap? = coverLoader?.load(book)

    private val refresh = MutableStateFlow(0)
    private val source = refresh.flatMapLatest {
        repository.observeBooks().map<List<Book>, LibraryState> { LibraryState.Ready(it) }
            .onStart { emit(LibraryState.Loading) }
            .catch { emit(LibraryState.Error) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState.Loading)
    val query = savedState.getStateFlow("libraryQuery", "")
    private val preferencesController = LibraryPreferencesController(libraryPreferences, savedState, viewModelScope)
    val sort = preferencesController.sort
    val preferenceStatus = preferencesController.status
    private val filterName = savedState.getStateFlow("libraryFilter", LibraryFilter.ALL.name)
    val filter = filterName.map { name -> LibraryFilter.entries.firstOrNull { it.name == name } ?: LibraryFilter.ALL }
        .stateIn(viewModelScope, SharingStarted.Eagerly,
            LibraryFilter.entries.firstOrNull { it.name == filterName.value } ?: LibraryFilter.ALL)
    val layout = preferencesController.layout
    private val organizationFilterValues = savedState.getStateFlow("libraryOrganizationFilters", arrayListOf<String>())
    val organizationFilters = organizationFilterValues.map { LibraryOrganizationFilters.fromSavedValues(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly,
            LibraryOrganizationFilters.fromSavedValues(organizationFilterValues.value))
    val library = combine(source, query, sort, filter, organizationFilters) { state, query, sort, filter, organizationFilters ->
        if (state is LibraryState.Ready) state.copy(
            books = sortBooks(filterOrganizationBooks(filterBooks(searchBooks(state.books, query), filter),
                organizationFilters), sort), query = query,
        ) else state
    }.flowOn(searchDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState.Loading)

    fun search(query: String) { savedState["libraryQuery"] = query.take(200) }
    fun chooseSort(sort: LibrarySort) { preferencesController.chooseSort(sort) }
    fun chooseFilter(filter: LibraryFilter) { savedState["libraryFilter"] = filter.name }
    fun chooseOrganizationFilters(filters: LibraryOrganizationFilters) {
        savedState["libraryOrganizationFilters"] = filters.savedValues()
    }
    fun clearFilters() {
        search("")
        chooseFilter(LibraryFilter.ALL)
        chooseOrganizationFilters(LibraryOrganizationFilters())
    }
    fun chooseLayout(layout: LibraryLayout) { preferencesController.chooseLayout(layout) }
    fun retryPreferences() { preferencesController.retry() }

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()
    private val pendingImport = savedState.getStateFlow(PENDING_IMPORT, false)
    val importPickerPending = pendingImport
    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress = _importProgress.asStateFlow()
    private val _notice = MutableStateFlow<ImportNotice?>(null)
    val notice = _notice.asStateFlow()
    private var noticeId = 0L
    private val _exporting = MutableStateFlow(false)
    val exporting = _exporting.asStateFlow()
    private val _exportNotice = MutableStateFlow<ExportNotice?>(null)
    val exportNotice = _exportNotice.asStateFlow()
    private var exportNoticeId = 0L
    private val studyExportController = StudyExportController(studyExporter, savedState, viewModelScope)
    val studyExportAvailable: Boolean get() = studyExportController.available
    val studyExporting = studyExportController.exporting
    val studyExportPickerPending = studyExportController.pickerPending
    val studyExportRequest = studyExportController.request
    val studyExportNotice = studyExportController.notice
    private val studyExportBusy: Boolean get() = studyExportController.busy
    private val coverEditController = CoverEditController(coverEditor, savedState, viewModelScope, repository::findById)
    val coverEditAvailable: Boolean get() = coverEditController.available
    val coverEdit = coverEditController.state
    val coverPickerPending = coverEditController.pickerPending
    val coverEditing = coverEditController.running
    private val coverBusy: Boolean get() = coverEditController.busy
    private val discoveryController = DiscoveryController(discovery, savedState, viewModelScope)
    val discoveryAvailable: Boolean get() = discoveryController.available
    val discoveryState = discoveryController.state
    private val bookFolderController = BookFolderController(bookFolders, viewModelScope,
        mayMutate = { source.value is LibraryState.Ready && !otherDiscoveryMutationBusyBase &&
            (discoveryState.value is DiscoveryState.Idle || discoveryState.value is DiscoveryState.Preview) },
        preview = { discoveryState.value as? DiscoveryState.Preview },
        beginScan = { uri ->
            if (discoveryState.value == DiscoveryState.Idle && discoveryController.prepare()) {
                discoveryController.onTreeResult(uri); true
            } else false
        }, currentScanId = { (discoveryState.value as? DiscoveryState.Scanning)?.sessionId },
        cancelOpeningScan = { session -> discoveryController.cancel(session) })
    val bookFolderState = bookFolderController.state
    fun bindBookFolderOwner(owner: String, resumed: Boolean) { bookFolderController.bind(owner, resumed) }
    fun releaseBookFolderOwner(owner: String) { bookFolderController.release(owner) }
    fun reloadBookFolder() { bookFolderController.reload() }
    fun rememberBookFolder(owner: String, sessionId: String, treeUri: String, expected: com.yaprak.reader.domain.library.BookFolderSnapshot) {
        bookFolderController.remember(owner, sessionId, treeUri, expected)
    }
    fun forgetBookFolder(owner: String, expected: com.yaprak.reader.domain.library.BookFolderSnapshot) { bookFolderController.forget(owner, expected) }
    fun scanBookFolder(owner: String, expected: com.yaprak.reader.domain.library.BookFolderSnapshot) { bookFolderController.scan(owner, expected) }
    fun cancelBookFolderScan(owner: String) { bookFolderController.cancelScan(owner) }
    fun reportBookFolderOpeningUi(owner: String, loaded: Boolean, eligible: Boolean) {
        bookFolderController.reportOpeningUi(owner, loaded, eligible)
    }
    fun setBookFolderAutomaticScan(owner: String, expected: com.yaprak.reader.domain.library.BookFolderSnapshot, enabled: Boolean) {
        bookFolderController.setAutomaticScan(owner, expected, enabled)
    }
    private val discoveryBusy: Boolean get() = discoveryController.busy
    private val _removal = MutableStateFlow<BookRemovalState>(BookRemovalState.Idle)
    val removal = _removal.asStateFlow()
    private val _removalNotice = MutableStateFlow<BookRemovalNotice?>(null)
    val removalNotice = _removalNotice.asStateFlow()
    private var removalNoticeId = 0L
    private val _metadataEdit = MutableStateFlow<BookMetadataEditState>(BookMetadataEditState.Idle)
    val metadataEdit = _metadataEdit.asStateFlow()
    private var metadataEventId = 0L
    private val _organizationEdit = MutableStateFlow<BookOrganizationEditState>(BookOrganizationEditState.Idle)
    val organizationEdit = _organizationEdit.asStateFlow()
    private var organizationEventId = 0L
    private val editingBusy: Boolean get() = _metadataEdit.value is BookMetadataEditState.Saving ||
        _organizationEdit.value is BookOrganizationEditState.Saving
    private val pendingExportBookId = savedState.getStateFlow<String?>(PENDING_EXPORT_BOOK_ID, null)
    val exportPickerPending = pendingExportBookId.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, pendingExportBookId.value != null)
    val backupAvailable: Boolean get() = backup != null
    private val pendingBackupName = savedState.getStateFlow<String?>(PENDING_BACKUP_OPERATION, null)
    val backupPickerPending = pendingBackupName.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, pendingBackupName.value != null)
    private val _backupRunning = MutableStateFlow<BackupOperation?>(null)
    val backupRunning = _backupRunning.asStateFlow()
    private val _backupNotice = MutableStateFlow<BackupNotice?>(null)
    val backupNotice = _backupNotice.asStateFlow()
    private var backupNoticeId = 0L
    private val backupBusy: Boolean get() = pendingBackupName.value != null || _backupRunning.value != null

    /** A picker reservation survives Activity recreation without storing document URIs. */
    fun prepareImport(): Boolean {
        if (importBusy) return false
        savedState[PENDING_IMPORT] = true
        return true
    }

    fun onImportDocumentResult(uris: List<String>) {
        if (studyExportBusy || coverBusy || discoveryBusy || bookFolderState.value.busy) return
        if (!pendingImport.value) return
        savedState[PENDING_IMPORT] = false
        if (uris.isEmpty()) return
        startImport(uris)
    }

    fun onImportLaunchFailed() {
        if (!pendingImport.value) return
        savedState[PENDING_IMPORT] = false
        _notice.value = ImportNotice(++noticeId, listOf(ImportResult.Failed(ImportFailure.ACCESS)), 0)
    }

    /** Used by callers that already own a single document URI. */
    fun importBook(uri: String) {
        if (importBusy) return
        startImport(listOf(uri))
    }

    /** Finish the current document normally, then leave the remaining selection unprocessed. */
    fun requestStopRemainingImports() {
        if (!_importing.value) return
        val progress = _importProgress.value ?: return
        if (!progress.stopRequested) _importProgress.value = progress.copy(stopRequested = true)
    }

    private val otherCoverMutationBusy: Boolean get() = pendingImport.value || _importing.value ||
        _exporting.value || pendingExportBookId.value != null ||
        _removal.value is BookRemovalState.Removing ||
        editingBusy || backupBusy || studyExportBusy || discoveryBusy || bookFolderState.value.busy
    private val importBusy: Boolean get() = otherCoverMutationBusy || coverBusy

    private fun startImport(uris: List<String>) {
        val selected = uris.take(MAX_IMPORT_SELECTION)
        if (selected.isEmpty()) return
        val skipped = uris.size - selected.size
        _importing.value = true
        _importProgress.value = ImportProgress(0, selected.size)
        viewModelScope.launch {
            try {
                val results = ArrayList<ImportResult>(selected.size)
                for (uri in selected) {
                    currentCoroutineContext().ensureActive()
                    if (_importProgress.value?.stopRequested == true) break
                    val result = try {
                        importer.import(uri)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ImportResult.Failed(ImportFailure.UNEXPECTED)
                    }
                    currentCoroutineContext().ensureActive()
                    results += result
                    _importProgress.value = _importProgress.value?.copy(completed = results.size)
                }
                currentCoroutineContext().ensureActive()
                _notice.value = ImportNotice(++noticeId, results, skipped, stopped = selected.size - results.size)
            } finally {
                _importProgress.value = null
                _importing.value = false
            }
        }
    }

    fun retry() { refresh.value += 1 }
    fun prepareDiscovery(): Boolean {
        if (importBusy) return false
        return discoveryController.prepare()
    }
    fun onDiscoveryTreeResult(uri: String?) {
        if (uri != null && otherDiscoveryMutationBusy) discoveryController.rejectPending()
        else discoveryController.onTreeResult(uri)
    }
    fun onDiscoveryLaunchFailed() { discoveryController.onLaunchFailed() }
    fun searchDiscovery(sessionId: String, query: String) { if (!bookFolderState.value.busy) discoveryController.search(sessionId, query) }
    fun selectDiscoveredBook(sessionId: String, candidateId: String, selected: Boolean) {
        if (!bookFolderState.value.busy) discoveryController.select(sessionId, candidateId, selected)
    }
    fun selectAllDiscoveredBooks(sessionId: String) { if (!bookFolderState.value.busy) discoveryController.selectAllMatching(sessionId) }
    fun clearDiscoveredSelection(sessionId: String) { if (!bookFolderState.value.busy) discoveryController.clearSelection(sessionId) }
    fun dismissDiscovery(sessionId: String) { if (!bookFolderState.value.busy) discoveryController.dismiss(sessionId) }
    fun cancelDiscovery(sessionId: String) { discoveryController.cancel(sessionId) }
    fun importDiscoveredBooks(sessionId: String) {
        if (otherDiscoveryMutationBusy) return
        val selected = discoveryController.consumeSelection(sessionId) ?: return
        // Consume the exact preview and enter the existing sequential queue in one main-thread call.
        startImport(selected)
    }
    private val otherDiscoveryMutationBusy: Boolean get() = otherDiscoveryMutationBusyBase || bookFolderState.value.busy
    private val otherDiscoveryMutationBusyBase: Boolean get() = pendingImport.value || _importing.value ||
        _exporting.value || pendingExportBookId.value != null || _removal.value is BookRemovalState.Removing ||
        editingBusy || backupBusy || studyExportBusy || coverBusy
    fun acknowledgeNotice(id: Long) { if (_notice.value?.id == id) _notice.value = null }
    fun acknowledgeExportNotice(id: Long) { if (_exportNotice.value?.id == id) _exportNotice.value = null }
    fun acknowledgeStudyExportNotice(id: Long) { studyExportController.acknowledgeNotice(id) }
    fun clearCoverEditState(bookId: String, sha256: String) { coverEditController.clearFinished(bookId, sha256) }

    fun prepareCoverChange(book: Book): Boolean {
        if (importBusy || !currentCoverIdentity(book)) return false
        return coverEditController.prepare(book)
    }

    fun onCoverDocumentResult(uri: String?) {
        if (uri != null && otherCoverMutationBusy) coverEditController.rejectPending()
        else coverEditController.onDocumentResult(uri)
    }
    fun onCoverLaunchFailed() { coverEditController.onLaunchFailed() }
    fun resetBookCover(book: Book) {
        if (importBusy || !currentCoverIdentity(book)) return
        coverEditController.reset(book)
    }

    private fun currentCoverIdentity(book: Book): Boolean {
        val current = (source.value as? LibraryState.Ready)?.books?.singleOrNull { it.id == book.id }
        return current?.sha256 == book.sha256 && current?.customCover?.id == book.customCover?.id
    }
    fun acknowledgeRemovalNotice(id: Long) { if (_removalNotice.value?.id == id) _removalNotice.value = null }
    fun acknowledgeBackupNotice(id: Long) { if (_backupNotice.value?.id == id) _backupNotice.value = null }
    fun clearRemovalError(bookId: String) {
        if ((_removal.value as? BookRemovalState.Error)?.bookId == bookId) _removal.value = BookRemovalState.Idle
    }

    fun clearBookMetadataState(bookId: String) {
        val current = _metadataEdit.value
        if (current !is BookMetadataEditState.Saving && when (current) {
                is BookMetadataEditState.Failed -> current.bookId == bookId
                is BookMetadataEditState.Saved -> current.bookId == bookId
                else -> false
            }) _metadataEdit.value = BookMetadataEditState.Idle
    }

    fun saveBookMetadata(book: Book, title: String, authors: String?) {
        if (importBusy) return
        val cleanTitle = title.trim()
        val cleanAuthors = authors?.trim()?.ifBlank { null }
        if (cleanTitle.isEmpty() || cleanTitle.length > 1000 || (cleanAuthors?.length ?: 0) > 2000) {
            _metadataEdit.value = BookMetadataEditState.Failed(book.id)
            return
        }
        _metadataEdit.value = BookMetadataEditState.Saving(book.id)
        viewModelScope.launch {
            val saved = try {
                repository.updateMetadata(book.id, book.sha256, cleanTitle, cleanAuthors)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            _metadataEdit.value = if (saved) BookMetadataEditState.Saved(book.id, ++metadataEventId)
                else BookMetadataEditState.Failed(book.id)
        }
    }

    fun clearBookOrganizationState(bookId: String, sha256: String) {
        val current = _organizationEdit.value
        val matches = when (current) {
            is BookOrganizationEditState.Failed -> current.bookId == bookId && current.sha256 == sha256
            is BookOrganizationEditState.Saved -> current.bookId == bookId && current.sha256 == sha256
            else -> false
        }
        if (matches) _organizationEdit.value = BookOrganizationEditState.Idle
    }

    fun saveBookOrganization(book: Book, organization: BookOrganization) {
        if (importBusy) return
        val current = (source.value as? LibraryState.Ready)?.books?.singleOrNull { it.id == book.id }
        val clean = try { organization.normalized() } catch (_: IllegalArgumentException) { null }
        if (current?.sha256 != book.sha256 || current?.organization != book.organization || clean == null) {
            _organizationEdit.value = BookOrganizationEditState.Failed(book.id, book.sha256)
            return
        }
        _organizationEdit.value = BookOrganizationEditState.Saving(book.id, book.sha256)
        viewModelScope.launch {
            val saved = try {
                repository.updateOrganization(book.id, book.sha256, clean)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            currentCoroutineContext().ensureActive()
            _organizationEdit.value = if (saved)
                BookOrganizationEditState.Saved(book.id, book.sha256, ++organizationEventId)
            else BookOrganizationEditState.Failed(book.id, book.sha256)
        }
    }

    fun removeBook(book: Book) {
        val currentRemover = remover ?: return
        if (_removal.value is BookRemovalState.Removing || pendingImport.value || _importing.value || _exporting.value || pendingExportBookId.value != null ||
            editingBusy || backupBusy || studyExportBusy || coverBusy || discoveryBusy || bookFolderState.value.busy) return
        _removal.value = BookRemovalState.Removing(book.id)
        viewModelScope.launch {
            val result = try {
                currentRemover.remove(book.id, book.sha256)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BookRemovalResult.Failed
            }
            when (result) {
                is BookRemovalResult.Removed -> {
                    _removalNotice.value = BookRemovalNotice(++removalNoticeId, result)
                    _removal.value = BookRemovalState.Idle
                }
                else -> _removal.value = BookRemovalState.Error(book.id, result)
            }
        }
    }

    /** Reserves one picker result for one book; a second tap cannot retarget its URI. */
    fun prepareExport(book: Book): BookExportRequest? {
        if (exporter == null || pendingExportBookId.value != null || _exporting.value || pendingImport.value || _importing.value ||
            _removal.value is BookRemovalState.Removing || editingBusy || backupBusy || studyExportBusy || coverBusy || discoveryBusy || bookFolderState.value.busy) return null
        savedState[PENDING_EXPORT_BOOK_ID] = book.id
        savedState[PENDING_EXPORT_FORMAT] = book.format.name
        return BookExportRequest(book.format, suggestedExportFileName(book), book.originalExtension == "fb2.zip")
    }

    fun onExportDocumentResult(format: BookFormat, destinationUri: String?) {
        if (studyExportBusy || coverBusy || discoveryBusy || bookFolderState.value.busy) return
        val bookId = pendingExportBookId.value ?: return
        if (savedState.get<String>(PENDING_EXPORT_FORMAT) != format.name) return
        savedState[PENDING_EXPORT_BOOK_ID] = null
        savedState[PENDING_EXPORT_FORMAT] = null
        if (destinationUri == null) return
        val currentExporter = exporter ?: return
        _exporting.value = true
        viewModelScope.launch {
            try {
                val result = try {
                    currentExporter.export(bookId, destinationUri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ExportResult.Failed(ExportFailure.IO, partialFileMayRemain = true)
                }
                _exportNotice.value = ExportNotice(++exportNoticeId, result)
            } finally {
                _exporting.value = false
            }
        }
    }

    fun onExportLaunchFailed(format: BookFormat) {
        if (pendingExportBookId.value == null || savedState.get<String>(PENDING_EXPORT_FORMAT) != format.name) return
        savedState[PENDING_EXPORT_BOOK_ID] = null
        savedState[PENDING_EXPORT_FORMAT] = null
        _exportNotice.value = ExportNotice(++exportNoticeId, ExportResult.Failed(ExportFailure.ACCESS))
    }

    fun prepareStudyExport(book: Book): String? {
        if (importBusy) return null
        return studyExportController.prepare(book)
    }

    fun prepareStudyExport(book: Book, format: StudyExportFormat): BookStudyExportRequest? {
        if (importBusy || (source.value as? LibraryState.Ready)?.books?.singleOrNull { it.id == book.id } != book)
            return null
        return studyExportController.prepare(book, format)
    }

    fun claimStudyExportLaunch(requestId: String, format: StudyExportFormat): Boolean =
        studyExportController.claimLaunch(requestId, format)

    fun onStudyExportDocumentResult(requestId: String, format: StudyExportFormat, uri: String?) {
        studyExportController.onDocumentResult(requestId, format, uri)
    }

    fun onStudyExportLaunchFailed(requestId: String, format: StudyExportFormat) {
        studyExportController.onLaunchFailed(requestId, format)
    }

    fun onStudyExportDocumentResult(uri: String?) { studyExportController.onDocumentResult(uri) }
    fun onStudyExportLaunchFailed() { studyExportController.onLaunchFailed() }

    /** Reserve a single SAF picker result so a recreated Activity cannot retarget it. */
    fun prepareBackup(operation: BackupOperation): Boolean {
        if (backup == null || backupBusy || pendingImport.value || _importing.value || _exporting.value ||
            _removal.value is BookRemovalState.Removing || editingBusy ||
            pendingExportBookId.value != null || studyExportBusy || coverBusy || discoveryBusy || bookFolderState.value.busy) return false
        if (operation == BackupOperation.RESTORE) {
            val ready = library.value as? LibraryState.Ready ?: return false
            if (ready.totalCount != 0) {
                _backupNotice.value = BackupNotice(++backupNoticeId, BackupResult.Failed(BackupFailure.NOT_EMPTY))
                return false
            }
        }
        savedState[PENDING_BACKUP_OPERATION] = operation.name
        return true
    }

    fun onBackupDocumentResult(operation: BackupOperation, uri: String?) {
        if (studyExportBusy || coverBusy || discoveryBusy || bookFolderState.value.busy) return
        if (pendingBackupName.value != operation.name) return
        savedState[PENDING_BACKUP_OPERATION] = null
        if (uri == null) return
        val currentBackup = backup ?: return
        _backupRunning.value = operation
        viewModelScope.launch {
            try {
                val result = try {
                    when (operation) {
                        BackupOperation.CREATE -> currentBackup.create(uri)
                        BackupOperation.RESTORE -> currentBackup.restore(uri)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    BackupResult.Failed(BackupFailure.IO, partialFileMayRemain = operation == BackupOperation.CREATE)
                }
                _backupNotice.value = BackupNotice(++backupNoticeId, result)
            } finally {
                _backupRunning.value = null
            }
        }
    }

    fun onBackupLaunchFailed(operation: BackupOperation) {
        if (pendingBackupName.value != operation.name) return
        savedState[PENDING_BACKUP_OPERATION] = null
        _backupNotice.value = BackupNotice(++backupNoticeId, BackupResult.Failed(BackupFailure.ACCESS))
    }

    private companion object {
        const val PENDING_EXPORT_BOOK_ID = "pendingExportBookId"
        const val PENDING_EXPORT_FORMAT = "pendingExportFormat"
        const val PENDING_BACKUP_OPERATION = "pendingBackupOperation"
        const val PENDING_IMPORT = "pendingImport"
    }
}
