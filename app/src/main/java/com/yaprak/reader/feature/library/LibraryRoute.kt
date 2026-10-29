package com.yaprak.reader.feature.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.UUID
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.ExportFailure
import com.yaprak.reader.domain.library.ExportResult
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.domain.library.ImportResult
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportResult
import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.backup.BackupResult
import kotlinx.coroutines.CancellationException

internal class OpenLocalBooks : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

internal class OpenLocalCoverImage : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, MIME_TYPES)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)

    companion object {
        val MIME_TYPES: Array<String> get() = arrayOf("image/jpeg", "image/png", "image/webp")
    }
}

internal class OpenLocalBookFolder : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

internal class CreateLocalBook(mimeType: String) : ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

internal class CreateBackupDocument : ActivityResultContracts.CreateDocument("application/zip")
internal class OpenBackupDocument : ActivityResultContracts.OpenDocument()

@Composable
fun LibraryRoute(viewModel: LibraryViewModel, onAllNotes: (() -> Unit)? = null,
    onTextSearch: (() -> Unit)? = null, onStatistics: (() -> Unit)? = null,
    onWords: (() -> Unit)? = null, onLabels: (() -> Unit)? = null) {
    val state by viewModel.library.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val organizationFilters by viewModel.organizationFilters.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val preferenceStatus by viewModel.preferenceStatus.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val importPickerPending by viewModel.importPickerPending.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val exportPickerPending by viewModel.exportPickerPending.collectAsStateWithLifecycle()
    val studyExporting by viewModel.studyExporting.collectAsStateWithLifecycle()
    val studyExportPickerPending by viewModel.studyExportPickerPending.collectAsStateWithLifecycle()
    val studyExportRequest by viewModel.studyExportRequest.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val exportNotice by viewModel.exportNotice.collectAsStateWithLifecycle()
    val studyExportNotice by viewModel.studyExportNotice.collectAsStateWithLifecycle()
    val removal by viewModel.removal.collectAsStateWithLifecycle()
    val removalNotice by viewModel.removalNotice.collectAsStateWithLifecycle()
    val metadataEdit by viewModel.metadataEdit.collectAsStateWithLifecycle()
    val organizationEdit by viewModel.organizationEdit.collectAsStateWithLifecycle()
    val coverEdit by viewModel.coverEdit.collectAsStateWithLifecycle()
    val discoveryState by viewModel.discoveryState.collectAsStateWithLifecycle()
    val bookFolderState by viewModel.bookFolderState.collectAsStateWithLifecycle()
    val folderLifecycle = LocalLifecycleOwner.current.lifecycle
    val folderOwner = remember(viewModel) { UUID.randomUUID().toString() }
    DisposableEffect(viewModel, folderLifecycle, folderOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            viewModel.bindBookFolderOwner(folderOwner, folderLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        folderLifecycle.addObserver(observer)
        viewModel.bindBookFolderOwner(folderOwner, folderLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { folderLifecycle.removeObserver(observer); viewModel.releaseBookFolderOwner(folderOwner) }
    }
    val backupRunning by viewModel.backupRunning.collectAsStateWithLifecycle()
    val backupPickerPending by viewModel.backupPickerPending.collectAsStateWithLifecycle()
    val backupNotice by viewModel.backupNotice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val coverRequest: (suspend (Book) -> Bitmap?)? = remember(viewModel) {
        if (viewModel.coversAvailable) {
            val request: suspend (Book) -> Bitmap? = { book -> viewModel.loadCover(book) }
            request
        } else null
    }
    val picker = rememberLauncherForActivityResult(OpenLocalBooks()) { uris ->
        viewModel.onImportDocumentResult(uris.map { it.toString() })
    }
    val epubExportPicker = rememberLauncherForActivityResult(CreateLocalBook("application/epub+zip")) { uri ->
        viewModel.onExportDocumentResult(BookFormat.EPUB, uri?.toString())
    }
    val txtExportPicker = rememberLauncherForActivityResult(CreateLocalBook("text/plain")) { uri ->
        viewModel.onExportDocumentResult(BookFormat.TXT, uri?.toString())
    }
    val docxExportPicker = rememberLauncherForActivityResult(CreateLocalBook(com.yaprak.reader.formats.docx.DOCX_MIME_TYPE)) { uri ->
        viewModel.onExportDocumentResult(BookFormat.DOCX, uri?.toString())
    }
    val fb2ExportPicker = rememberLauncherForActivityResult(CreateLocalBook("application/x-fictionbook+xml")) { uri ->
        viewModel.onExportDocumentResult(BookFormat.FB2, uri?.toString())
    }
    val fb2ZipExportPicker = rememberLauncherForActivityResult(CreateLocalBook("application/zip")) { uri ->
        viewModel.onExportDocumentResult(BookFormat.FB2, uri?.toString())
    }
    val cbrExportPicker = rememberLauncherForActivityResult(CreateLocalBook("application/vnd.comicbook-rar")) { uri ->
        viewModel.onExportDocumentResult(BookFormat.CBR, uri?.toString())
    }
    val cbzExportPicker = rememberLauncherForActivityResult(CreateLocalBook("application/vnd.comicbook+zip")) { uri ->
        viewModel.onExportDocumentResult(BookFormat.CBZ, uri?.toString())
    }
    val pdfExportPicker = rememberLauncherForActivityResult(CreateLocalBook("application/pdf")) { uri ->
        viewModel.onExportDocumentResult(BookFormat.PDF, uri?.toString())
    }
    studyExportRequest?.let { request -> key(request.requestId) {
        StudyExportPicker(request, viewModel::claimStudyExportLaunch,
            viewModel::onStudyExportDocumentResult, viewModel::onStudyExportLaunchFailed)
    } }
    val coverPicker = rememberLauncherForActivityResult(OpenLocalCoverImage()) { uri ->
        viewModel.onCoverDocumentResult(uri?.toString())
    }
    val folderPicker = rememberLauncherForActivityResult(OpenLocalBookFolder()) { uri ->
        viewModel.onDiscoveryTreeResult(uri?.toString())
    }
    val backupCreatePicker = rememberLauncherForActivityResult(CreateBackupDocument()) { uri ->
        viewModel.onBackupDocumentResult(BackupOperation.CREATE, uri?.toString())
    }
    val backupRestorePicker = rememberLauncherForActivityResult(OpenBackupDocument()) { uri ->
        viewModel.onBackupDocumentResult(BackupOperation.RESTORE, uri?.toString())
    }
    LaunchedEffect(notice?.id) {
        notice?.takeIf { !it.isBatch }?.let { current ->
            current.results.singleOrNull()?.let { result ->
                snackbar.showSnackbar(context.getString(result.messageResource()))
                viewModel.acknowledgeNotice(current.id)
            }
        }
    }
    LaunchedEffect(exportNotice?.id) {
        exportNotice?.let {
            snackbar.showSnackbar(it.result.message(context))
            viewModel.acknowledgeExportNotice(it.id)
        }
    }
    LaunchedEffect(studyExportNotice?.id) {
        studyExportNotice?.let {
            snackbar.showSnackbar(it.result.message(context))
            viewModel.acknowledgeStudyExportNotice(it.id)
        }
    }
    LaunchedEffect(removalNotice?.id) {
        removalNotice?.let {
            snackbar.showSnackbar(if (it.result.privateFileMayRemain)
                context.getString(R.string.remove_success_file_remains)
                else context.getString(R.string.remove_success))
            viewModel.acknowledgeRemovalNotice(it.id)
        }
    }
    LaunchedEffect(backupNotice?.id) {
        backupNotice?.let {
            snackbar.showSnackbar(it.result.message(context))
            viewModel.acknowledgeBackupNotice(it.id)
        }
    }
    LibraryScreen(
        state = state,
        importing = importing,
        importProgress = importProgress,
        importPickerPending = importPickerPending,
        importNotice = notice?.takeIf { it.isBatch },
        onDismissImportNotice = { notice?.let { viewModel.acknowledgeNotice(it.id) } },
        onStopRemainingImports = viewModel::requestStopRemainingImports,
        exporting = exporting || exportPickerPending || studyExporting || studyExportPickerPending,
        studyExporting = studyExporting || studyExportPickerPending,
        removal = removal,
        removalNotice = removalNotice,
        onRemove = viewModel::removeBook,
        onClearRemovalError = viewModel::clearRemovalError,
        metadataEdit = metadataEdit,
        onSaveMetadata = viewModel::saveBookMetadata,
        onClearMetadataState = viewModel::clearBookMetadataState,
        organizationEdit = organizationEdit,
        onSaveOrganization = viewModel::saveBookOrganization,
        onClearOrganizationState = viewModel::clearBookOrganizationState,
        coverEdit = coverEdit,
        onChangeCover = if (viewModel.coverEditAvailable) ({ book ->
            if (viewModel.prepareCoverChange(book)) {
                try { coverPicker.launch(OpenLocalCoverImage.MIME_TYPES) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RuntimeException) { viewModel.onCoverLaunchFailed() }
            }
        }) else null,
        onResetCover = if (viewModel.coverEditAvailable) viewModel::resetBookCover else null,
        onClearCoverEditState = viewModel::clearCoverEditState,
        discoveryState = discoveryState,
        onDiscover = if (viewModel.discoveryAvailable) ({
            if (viewModel.prepareDiscovery()) {
                try { folderPicker.launch(null) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RuntimeException) { viewModel.onDiscoveryLaunchFailed() }
            }
        }) else null,
        onDiscoverySearch = viewModel::searchDiscovery,
        onDiscoverySelect = viewModel::selectDiscoveredBook,
        onDiscoverySelectAll = viewModel::selectAllDiscoveredBooks,
        onDiscoveryClearSelection = viewModel::clearDiscoveredSelection,
        onDiscoveryImport = viewModel::importDiscoveredBooks,
        onDiscoveryDismiss = viewModel::dismissDiscovery,
        onDiscoveryCancel = viewModel::cancelDiscovery,
        bookFolderState = bookFolderState,
        onRememberFolder = { session, uri, expected -> viewModel.rememberBookFolder(folderOwner, session, uri, expected) },
        onSavedFolderScan = { expected -> viewModel.scanBookFolder(folderOwner, expected) },
        onForgetFolder = { expected -> viewModel.forgetBookFolder(folderOwner, expected) },
        onReloadFolder = viewModel::reloadBookFolder, onCancelFolderScan = { viewModel.cancelBookFolderScan(folderOwner) },
        onFolderAutomatic = { expected, enabled -> viewModel.setBookFolderAutomaticScan(folderOwner, expected, enabled) },
        onFolderOpeningUi = { loaded, eligible -> viewModel.reportBookFolderOpeningUi(folderOwner, loaded, eligible) },
        onAllNotes = onAllNotes,
        onTextSearch = onTextSearch,
        onStatistics = onStatistics,
        onWords = onWords,
        onLabels = onLabels,
        snackbarHostState = snackbar,
        onImport = {
            if (viewModel.prepareImport()) {
                try { picker.launch(arrayOf(com.yaprak.reader.formats.docx.DOCX_MIME_TYPE, "application/epub+zip", "application/pdf", "text/plain", "application/vnd.comicbook+zip", "application/x-cbz", "application/vnd.comicbook-rar", "application/x-cbr", "application/vnd.rar", "application/x-rar-compressed", "application/x-fictionbook+xml", "application/x-fictionbook+zip", "application/xml", "text/xml", "application/octet-stream", "application/zip", "application/x-zip-compressed")) }
                catch (_: RuntimeException) { viewModel.onImportLaunchFailed() }
            }
        },
        onRetry = viewModel::retry,
        onRead = { id -> context.startActivity(com.yaprak.reader.feature.reader.ReaderActivity.intent(context, id)) },
        onExport = { book ->
            viewModel.prepareExport(book)?.let { request ->
                try {
                    when (request.format) {
                        BookFormat.EPUB -> epubExportPicker.launch(request.suggestedFileName)
                        BookFormat.PDF -> pdfExportPicker.launch(request.suggestedFileName)
                        BookFormat.TXT -> txtExportPicker.launch(request.suggestedFileName)
                        BookFormat.DOCX -> docxExportPicker.launch(request.suggestedFileName)
                        BookFormat.CBZ -> cbzExportPicker.launch(request.suggestedFileName)
                        BookFormat.CBR -> cbrExportPicker.launch(request.suggestedFileName)
                        BookFormat.FB2 -> if (request.fb2Zip) fb2ZipExportPicker.launch(request.suggestedFileName)
                            else fb2ExportPicker.launch(request.suggestedFileName)
                    }
                } catch (_: RuntimeException) {
                    viewModel.onExportLaunchFailed(request.format)
                }
            }
        },
        onStudyExportFormat = if (viewModel.studyExportAvailable) ({ book, format ->
            viewModel.prepareStudyExport(book, format)
        }) else null,
        onSearch = viewModel::search,
        query = query,
        sort = sort,
        onSort = viewModel::chooseSort,
        filter = filter,
        onFilter = viewModel::chooseFilter,
        organizationFilters = organizationFilters,
        onOrganizationFilters = viewModel::chooseOrganizationFilters,
        onResetFilters = viewModel::clearFilters,
        layout = layout,
        onLayout = viewModel::chooseLayout,
        coverLoader = coverRequest,
        preferenceStatus = preferenceStatus,
        onRetryPreferences = viewModel::retryPreferences,
        backupAvailable = viewModel.backupAvailable,
        backupRunning = backupRunning,
        backupPickerPending = backupPickerPending,
        onBackupCreate = {
            if (viewModel.prepareBackup(BackupOperation.CREATE)) {
                try { backupCreatePicker.launch("Yaprak-yedek.yaprak") }
                catch (_: RuntimeException) { viewModel.onBackupLaunchFailed(BackupOperation.CREATE) }
            }
        },
        onBackupRestore = {
            if (viewModel.prepareBackup(BackupOperation.RESTORE)) {
                try { backupRestorePicker.launch(arrayOf("*/*")) }
                catch (_: RuntimeException) { viewModel.onBackupLaunchFailed(BackupOperation.RESTORE) }
            }
        },
    )
}

private fun BackupResult.message(context: Context): String = when (this) {
    is BackupResult.Created -> context.getString(R.string.backup_created, bookCount)
    is BackupResult.Restored -> context.getString(R.string.backup_restored, bookCount)
    is BackupResult.Failed -> {
        val resource = when (reason) {
            BackupFailure.NOT_EMPTY -> R.string.backup_error_not_empty
            BackupFailure.INVALID_ARCHIVE -> R.string.backup_error_invalid
            BackupFailure.UNSUPPORTED_VERSION -> R.string.backup_error_version
            BackupFailure.TOO_LARGE -> R.string.backup_error_too_large
            BackupFailure.CHANGED_SOURCE -> R.string.backup_error_changed_source
            BackupFailure.ACCESS -> R.string.backup_error_access
            BackupFailure.IO -> R.string.backup_error_io
            BackupFailure.RECOVERY_REQUIRED -> R.string.backup_error_recovery
        }
        context.getString(resource) + if (partialFileMayRemain) " " + context.getString(R.string.backup_partial_warning) else ""
    }
}

private fun ExportResult.message(context: Context): String = when (this) {
    ExportResult.Exported -> context.getString(R.string.export_success)
    is ExportResult.Failed -> {
        val resource = when (reason) {
            ExportFailure.MISSING_BOOK -> R.string.export_error_missing_book
            ExportFailure.CHANGED_SOURCE -> R.string.export_error_changed_source
            ExportFailure.ACCESS -> R.string.export_error_access
            ExportFailure.IO -> R.string.export_error_io
        }
        context.getString(resource) + if (partialFileMayRemain) " " + context.getString(R.string.export_partial_warning) else ""
    }
}

internal fun StudyExportResult.message(context: Context): String = when (this) {
    is StudyExportResult.Exported -> ankiCardCount?.let {
        context.getString(R.string.study_export_anki_study_success, it)
    } ?: context.getString(R.string.study_export_success, noteCount, wordCount, passageCount)
    is StudyExportResult.Failed -> {
        val resource = when (reason) {
            StudyExportFailure.MISSING_BOOK -> R.string.export_error_missing_book
            StudyExportFailure.CHANGED_BOOK -> R.string.study_export_error_changed_book
            StudyExportFailure.NO_RECORDS -> R.string.study_export_error_no_records
            StudyExportFailure.TOO_LARGE -> R.string.study_export_error_too_large
            StudyExportFailure.ACCESS -> R.string.export_error_access
            StudyExportFailure.IO -> R.string.study_export_error_io
            StudyExportFailure.UNSUPPORTED_FORMAT -> R.string.study_export_error_unsupported_format
        }
        context.getString(resource) + if (partialFileMayRemain)
            " " + context.getString(R.string.export_partial_warning) else ""
    }
}

private fun ImportResult.messageResource(): Int = when (this) {
    ImportResult.Added -> R.string.import_added
    ImportResult.Duplicate -> R.string.import_duplicate
    is ImportResult.Failed -> reason.messageResource()
}

internal fun ImportFailure.messageResource(): Int = when (this) {
    ImportFailure.UNSUPPORTED -> R.string.error_unsupported
    ImportFailure.INVALID -> R.string.error_invalid
    ImportFailure.PROTECTED -> R.string.error_protected
    ImportFailure.TOO_LARGE -> R.string.error_too_large
    ImportFailure.ACCESS -> R.string.error_access
    ImportFailure.IO -> R.string.error_io
    ImportFailure.UNEXPECTED -> R.string.error_unexpected
}
