package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.backup.BackupResult
import com.yaprak.reader.domain.backup.LibraryBackup
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookExporter
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookImporter
import com.yaprak.reader.domain.library.BookRemovalResult
import com.yaprak.reader.domain.library.BookRemover
import com.yaprak.reader.domain.library.BookStudyExporter
import com.yaprak.reader.domain.library.ExportResult
import com.yaprak.reader.domain.library.ImportResult
import com.yaprak.reader.domain.library.LibraryLayout
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.domain.library.LibrarySort
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.library.StudyExportResult
import com.yaprak.reader.feature.library.BackupOperation
import com.yaprak.reader.feature.library.BookMetadataEditState
import com.yaprak.reader.feature.library.BookRemovalState
import com.yaprak.reader.feature.library.LibraryViewModel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StudyExportViewModelTest {
    private val book = Book("book", "Ada", "Deniz", BookFormat.EPUB, "Ada.EPUB", "stored.epub", 1, "original-hash", 1)
    private val other = book.copy(id = "other", sha256 = "other-hash")

    private class StudyExporter : BookStudyExporter {
        class Request(val bookId: String, val hash: String, val uri: String) {
            val completion = CompletableDeferred<StudyExportResult>()
        }
        val requests = mutableListOf<Request>()
        override suspend fun export(bookId: String, contentSha256: String, destinationUri: String): StudyExportResult {
            val request = Request(bookId, contentSha256, destinationUri)
            requests += request
            return request.completion.await()
        }
    }

    private class Operations(book: Book) : LibraryRepository, BookImporter, BookExporter, BookRemover, LibraryBackup {
        val books = MutableStateFlow(listOf(book))
        val imports = mutableListOf<String>()
        val exports = mutableListOf<String>()
        val removals = mutableListOf<String>()
        val metadataWrites = mutableListOf<String>()
        val backups = mutableListOf<String>()
        val imported = CompletableDeferred<ImportResult>()
        val exported = CompletableDeferred<ExportResult>()
        val removed = CompletableDeferred<BookRemovalResult>()
        val metadataSaved = CompletableDeferred<Boolean>()
        val backedUp = CompletableDeferred<BackupResult>()
        override fun observeBooks(): Flow<List<Book>> = books
        override suspend fun findById(id: String): Book? = books.value.find { it.id == id }
        override suspend fun findByHash(sha256: String): Book? = books.value.find { it.sha256 == sha256 }
        override suspend fun storedFileNames(): Set<String> = books.value.map(Book::storageName).toSet()
        override suspend fun add(book: Book) { books.value += book }
        override suspend fun updateMetadata(id: String, sha256: String, title: String, authors: String?): Boolean {
            metadataWrites += id
            return metadataSaved.await()
        }
        override suspend fun import(documentUri: String): ImportResult {
            imports += documentUri
            return imported.await()
        }
        override suspend fun export(bookId: String, destinationUri: String): ExportResult {
            exports += bookId
            return exported.await()
        }
        override suspend fun remove(bookId: String, contentSha256: String): BookRemovalResult {
            removals += bookId
            return removed.await()
        }
        override suspend fun create(destinationUri: String): BackupResult {
            backups += destinationUri
            return backedUp.await()
        }
        override suspend fun restore(sourceUri: String): BackupResult {
            backups += sourceUri
            return backedUp.await()
        }
        override suspend fun recoverPendingRestore() = Unit
    }

    private suspend fun TestScope.withModel(
        exporter: StudyExporter? = StudyExporter(),
        savedState: SavedStateHandle = SavedStateHandle(),
        operations: Operations = Operations(book),
        action: suspend (LibraryViewModel, ViewModelStore, Operations) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val vm = LibraryViewModel(operations, operations, savedState, dispatcher,
                exporter = operations, remover = operations, backup = operations, studyExporter = exporter)
                .also { owner.put("library", it) }
            action(vm, owner, operations)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    private fun assertOtherStartsBlocked(vm: LibraryViewModel, operations: Operations) {
        assertFalse(vm.prepareImport())
        vm.importBook("content://blocked-import")
        vm.onImportDocumentResult(listOf("content://unreserved-import"))
        assertNull(vm.prepareExport(book))
        assertFalse(vm.prepareBackup(BackupOperation.CREATE))
        assertFalse(vm.prepareBackup(BackupOperation.RESTORE))
        vm.removeBook(book)
        vm.saveBookMetadata(book, "Changed", "Author")
        assertNull(vm.prepareStudyExport(other))
        assertFalse(vm.importing.value)
        assertFalse(vm.exporting.value)
        assertNull(vm.backupRunning.value)
        assertEquals(BookRemovalState.Idle, vm.removal.value)
        assertEquals(BookMetadataEditState.Idle, vm.metadataEdit.value)
        assertTrue(operations.imports.isEmpty())
        assertTrue(operations.exports.isEmpty())
        assertTrue(operations.removals.isEmpty())
        assertTrue(operations.metadataWrites.isEmpty())
        assertTrue(operations.backups.isEmpty())
    }

    @Test fun `reservation immediately captures identity and cannot be retargeted or duplicated`() = runTest {
        val exporter = StudyExporter()
        val saved = SavedStateHandle()
        withModel(exporter, saved) { vm, _, _ ->
            assertTrue(vm.studyExportAvailable)
            assertEquals("Ada-calisma-kayitlari.txt", vm.prepareStudyExport(book))
            assertTrue(vm.studyExportPickerPending.value)
            assertFalse(vm.studyExporting.value)
            assertEquals(book.id, saved.get<String>("pendingStudyExportBookId"))
            assertEquals(book.sha256, saved.get<String>("pendingStudyExportContentSha256"))
            assertNull(vm.prepareStudyExport(other))
            vm.onStudyExportDocumentResult("content://first")
            assertFalse(vm.studyExportPickerPending.value)
            assertTrue(vm.studyExporting.value)
            vm.onStudyExportDocumentResult("content://duplicate")
            vm.onStudyExportLaunchFailed()
            runCurrent()
            val request = exporter.requests.single()
            assertEquals(book.id, request.bookId)
            assertEquals(book.sha256, request.hash)
            assertEquals("content://first", request.uri)
            assertNull(saved.get<String>("pendingStudyExportBookId"))
            assertNull(saved.get<String>("pendingStudyExportContentSha256"))
            assertFalse(saved.keys().map { saved.get<Any?>(it) }.contains("content://first"))
            assertNull(vm.studyExportNotice.value)
            val result = StudyExportResult.Exported(2, 3, 1)
            request.completion.complete(result)
            runCurrent()
            assertEquals(result, vm.studyExportNotice.value?.result)
            assertFalse(vm.studyExporting.value)
            assertNotNull(vm.prepareStudyExport(other))
        }
    }

    @Test fun `pending and running study export block existing starts while presentation remains editable`() = runTest {
        val exporter = StudyExporter()
        withModel(exporter) { vm, _, operations ->
            assertNotNull(vm.prepareStudyExport(book))
            assertOtherStartsBlocked(vm, operations)
            runCurrent()
            assertOtherStartsBlocked(vm, operations)
            vm.search("ada")
            vm.chooseSort(LibrarySort.AUTHOR)
            vm.chooseLayout(LibraryLayout.LIST)
            assertEquals("ada", vm.query.value)
            assertEquals(LibrarySort.AUTHOR, vm.sort.value)
            assertEquals(LibraryLayout.LIST, vm.layout.value)
            vm.onStudyExportDocumentResult("content://study")
            assertOtherStartsBlocked(vm, operations)
            runCurrent()
            assertOtherStartsBlocked(vm, operations)
            vm.search("deniz")
            vm.chooseSort(LibrarySort.TITLE)
            vm.chooseLayout(LibraryLayout.GRID)
            assertEquals("deniz", vm.query.value)
            assertEquals(LibrarySort.TITLE, vm.sort.value)
            assertEquals(LibraryLayout.GRID, vm.layout.value)
            exporter.requests.single().completion.complete(StudyExportResult.Exported(1, 0, 0))
            runCurrent()
            assertTrue(vm.prepareImport())
            vm.onImportDocumentResult(emptyList())
            assertNotNull(vm.prepareExport(book))
            vm.onExportDocumentResult(book.format, null)
            assertTrue(vm.prepareBackup(BackupOperation.CREATE))
            vm.onBackupDocumentResult(BackupOperation.CREATE, null)
            assertEquals(listOf(book), operations.books.value)
        }
    }

    @Test fun `existing picker reservations prevent a study export reservation`() = runTest {
        val pendingStates = listOf(
            mapOf("pendingImport" to true),
            mapOf("pendingExportBookId" to book.id, "pendingExportFormat" to book.format.name),
            mapOf("pendingBackupOperation" to BackupOperation.CREATE.name),
        )
        for (pending in pendingStates) {
            val exporter = StudyExporter()
            withModel(exporter, SavedStateHandle(pending)) { vm, _, _ ->
                assertNull(vm.prepareStudyExport(book))
                assertFalse(vm.studyExportPickerPending.value)
                vm.onStudyExportDocumentResult("content://unreserved")
                runCurrent()
                assertTrue(exporter.requests.isEmpty())
                assertNull(vm.studyExportNotice.value)
            }
        }
    }

    @Test fun `every existing running mutation prevents a study export reservation`() = runTest {
        val starts: List<(LibraryViewModel) -> Unit> = listOf(
            { it.importBook("content://import") },
            { it.prepareExport(book); it.onExportDocumentResult(book.format, "content://book-export") },
            { it.removeBook(book) },
            { it.saveBookMetadata(book, "Changed", null) },
            { it.prepareBackup(BackupOperation.CREATE); it.onBackupDocumentResult(BackupOperation.CREATE, "content://backup") },
        )
        for (start in starts) {
            val exporter = StudyExporter()
            withModel(exporter) { vm, _, operations ->
                start(vm)
                assertNull(vm.prepareStudyExport(book))
                runCurrent()
                assertEquals(1, operations.imports.size + operations.exports.size + operations.removals.size +
                    operations.metadataWrites.size + operations.backups.size)
                assertNull(vm.prepareStudyExport(book))
                assertFalse(vm.studyExportPickerPending.value)
                assertTrue(exporter.requests.isEmpty())
            }
        }
    }

    @Test fun `restored picker exports its original hash even when current book identity changes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val firstOwner = ViewModelStore()
        val restoredOwner = ViewModelStore()
        val exporter = StudyExporter()
        val saved = SavedStateHandle()
        val operations = Operations(book)
        try {
            val first = LibraryViewModel(operations, operations, saved, dispatcher, studyExporter = exporter)
                .also { firstOwner.put("library", it) }
            assertNotNull(first.prepareStudyExport(book))
            firstOwner.clear()
            operations.books.value = listOf(book.copy(sha256 = "changed-hash"), other)
            val restored = LibraryViewModel(operations, operations, saved, dispatcher, studyExporter = exporter)
                .also { restoredOwner.put("library", it) }
            assertTrue(restored.studyExportPickerPending.value)
            assertNull(restored.prepareStudyExport(other))
            assertTrue(exporter.requests.isEmpty())
            restored.onStudyExportDocumentResult("content://restored")
            runCurrent()
            val request = exporter.requests.single()
            assertEquals(book.id, request.bookId)
            assertEquals(book.sha256, request.hash)
            request.completion.complete(StudyExportResult.Failed(StudyExportFailure.CHANGED_BOOK))
            runCurrent()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.CHANGED_BOOK), restored.studyExportNotice.value?.result)
            assertFalse(restored.studyExporting.value)
            assertNotNull(restored.prepareStudyExport(other))
        } finally {
            firstOwner.clear()
            restoredOwner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `picker cancellation and launch failure release reservation without starting exporter`() = runTest {
        val exporter = StudyExporter()
        withModel(exporter) { vm, _, _ ->
            vm.onStudyExportDocumentResult("content://unreserved")
            vm.onStudyExportLaunchFailed()
            assertNull(vm.studyExportNotice.value)
            assertNotNull(vm.prepareStudyExport(book))
            vm.onStudyExportDocumentResult(null)
            assertFalse(vm.studyExportPickerPending.value)
            assertNull(vm.studyExportNotice.value)
            assertNotNull(vm.prepareStudyExport(other))
            vm.onStudyExportLaunchFailed()
            assertFalse(vm.studyExportPickerPending.value)
            assertEquals(StudyExportResult.Failed(StudyExportFailure.ACCESS), vm.studyExportNotice.value?.result)
            val id = requireNotNull(vm.studyExportNotice.value).id
            vm.onStudyExportLaunchFailed()
            assertEquals(id, vm.studyExportNotice.value?.id)
            runCurrent()
            assertTrue(exporter.requests.isEmpty())
            assertTrue(vm.prepareImport())
        }
    }

    @Test fun `typed failure preserves cleanup detail and stale acknowledgement cannot clear newer notice`() = runTest {
        val exporter = StudyExporter()
        withModel(exporter) { vm, _, _ ->
            assertNotNull(vm.prepareStudyExport(book))
            vm.onStudyExportDocumentResult("content://empty")
            val failure = StudyExportResult.Failed(StudyExportFailure.NO_RECORDS, partialFileMayRemain = true)
            exporter.requests[0].completion.complete(failure)
            runCurrent()
            assertEquals(failure, vm.studyExportNotice.value?.result)
            assertFalse(vm.studyExporting.value)
            val oldId = requireNotNull(vm.studyExportNotice.value).id
            assertNotNull(vm.prepareStudyExport(book))
            vm.onStudyExportDocumentResult("content://retry")
            val result = StudyExportResult.Exported(1, 2, 3)
            exporter.requests[1].completion.complete(result)
            runCurrent()
            val latest = requireNotNull(vm.studyExportNotice.value)
            assertTrue(latest.id > oldId)
            vm.acknowledgeStudyExportNotice(oldId)
            assertEquals(latest, vm.studyExportNotice.value)
            vm.acknowledgeStudyExportNotice(latest.id)
            assertNull(vm.studyExportNotice.value)
        }
    }

    @Test fun `unexpected export exception reports IO with possible partial target and allows retry`() = runTest {
        val exporter = StudyExporter()
        withModel(exporter) { vm, _, _ ->
            assertNotNull(vm.prepareStudyExport(book))
            vm.onStudyExportDocumentResult("content://failed")
            exporter.requests.single().completion.completeExceptionally(IOException("Provider unavailable"))
            runCurrent()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO, partialFileMayRemain = true),
                vm.studyExportNotice.value?.result)
            assertFalse(vm.studyExporting.value)
            assertNotNull(vm.prepareStudyExport(book))
        }
    }

    @Test fun `export cancellation publishes no false notice and clears running`() = runTest {
        val exporter = StudyExporter()
        withModel(exporter) { vm, _, _ ->
            assertNotNull(vm.prepareStudyExport(book))
            vm.onStudyExportDocumentResult("content://cancelled")
            exporter.requests.single().completion.completeExceptionally(CancellationException("Owner cancelled"))
            runCurrent()
            assertFalse(vm.studyExporting.value)
            assertFalse(vm.studyExportPickerPending.value)
            assertNull(vm.studyExportNotice.value)
            assertNotNull(vm.prepareStudyExport(book))
        }
    }

    @Test fun `clearing owner cancels running study export without notice`() = runTest {
        val exporter = StudyExporter()
        withModel(exporter) { vm, owner, _ ->
            assertNotNull(vm.prepareStudyExport(book))
            vm.onStudyExportDocumentResult("content://running")
            assertTrue(vm.studyExporting.value)
            owner.clear()
            runCurrent()
            assertFalse(vm.studyExporting.value)
            assertNull(vm.studyExportNotice.value)
            assertEquals(1, exporter.requests.size)
        }
    }

    @Test fun `cleared owner cannot start exporter from a pending result or leave running stuck`() = runTest {
        val exporter = StudyExporter()
        withModel(exporter) { vm, owner, _ ->
            assertNotNull(vm.prepareStudyExport(book))
            owner.clear()
            vm.onStudyExportDocumentResult("content://too-late")
            runCurrent()
            assertFalse(vm.studyExportPickerPending.value)
            assertFalse(vm.studyExporting.value)
            assertNull(vm.studyExportNotice.value)
            assertTrue(exporter.requests.isEmpty())
        }
    }

    @Test fun `incomplete restored identity is discarded and absent capability is inert`() = runTest {
        val exporter = StudyExporter()
        val saved = SavedStateHandle(mapOf("pendingStudyExportBookId" to book.id))
        withModel(exporter, saved) { vm, _, _ ->
            assertFalse(vm.studyExportPickerPending.value)
            vm.onStudyExportDocumentResult("content://incomplete")
            assertTrue(exporter.requests.isEmpty())
            assertNull(saved.get<String>("pendingStudyExportBookId"))
            assertNotNull(vm.prepareStudyExport(book))
        }
        withModel(exporter = null) { vm, _, _ ->
            assertFalse(vm.studyExportAvailable)
            assertNull(vm.prepareStudyExport(book))
            vm.onStudyExportDocumentResult("content://unavailable")
            vm.onStudyExportLaunchFailed()
            assertFalse(vm.studyExportPickerPending.value)
            assertFalse(vm.studyExporting.value)
            assertNull(vm.studyExportNotice.value)
            assertTrue(vm.prepareImport())
        }
    }

    @Test fun `suggested text filename reuses existing safe name sanitation`() = runTest {
        withModel { vm, _, _ ->
            val unsafe = book.copy(format = BookFormat.PDF, originalName = "../ kötü:\u0000dosya.PDF")
            val name = requireNotNull(vm.prepareStudyExport(unsafe))
            assertTrue(name.endsWith("-calisma-kayitlari.txt"))
            assertFalse(name.startsWith('.'))
            assertFalse(name.any { it.isISOControl() || it in "\\/:*?\"<>|" })
            assertFalse(name.contains(".pdf", ignoreCase = true))
        }
    }

    @Test fun `new format preparation validates current complete Book revision and loaded source`() = runTest {
        withModel { vm, _, operations ->
            assertNull(vm.prepareStudyExport(book, StudyExportFormat.MARKDOWN))
            runCurrent()
            assertNull(vm.prepareStudyExport(book.copy(title = "stale"), StudyExportFormat.MARKDOWN))
            assertNull(vm.prepareStudyExport(book.copy(sha256 = "stale"), StudyExportFormat.MARKDOWN))
            val request = requireNotNull(vm.prepareStudyExport(book, StudyExportFormat.MARKDOWN))
            assertEquals(StudyExportFormat.MARKDOWN, vm.studyExportRequest.value?.format)
            assertEquals(book.sha256, request.contentSha256)
            assertOtherStartsBlocked(vm, operations)
            assertTrue(vm.claimStudyExportLaunch(request.requestId, request.format))
            vm.onStudyExportDocumentResult(request.requestId, request.format, null)
            operations.books.value = listOf(book.copy(title = "changed"))
            runCurrent()
            assertNull(vm.prepareStudyExport(book, StudyExportFormat.TEXT))
            assertNotNull(vm.prepareStudyExport(book.copy(title = "changed"), StudyExportFormat.TEXT))
        }
    }

    @Test fun `new format path cannot start while another picker mutation is reserved`() = runTest {
        withModel { vm, _, _ ->
            runCurrent()
            assertTrue(vm.prepareImport())
            assertNull(vm.prepareStudyExport(book, StudyExportFormat.ANKI_TSV))
            vm.onImportDocumentResult(emptyList())
            val request = requireNotNull(vm.prepareStudyExport(book, StudyExportFormat.ANKI_TSV))
            assertNull(vm.prepareStudyExport(book, StudyExportFormat.TEXT))
            assertTrue(vm.claimStudyExportLaunch(request.requestId, request.format))
            vm.onStudyExportLaunchFailed(request.requestId, request.format)
            assertFalse(vm.studyExportPickerPending.value)
            assertTrue(vm.prepareImport())
        }
    }

    @Test fun `legacy exporter explicitly rejects new format without reporting invented success`() = runTest {
        val exporter = StudyExporter()
        withModel(exporter) { vm, _, _ ->
            runCurrent()
            val request = requireNotNull(vm.prepareStudyExport(book, StudyExportFormat.MARKDOWN))
            assertTrue(vm.claimStudyExportLaunch(request.requestId, request.format))
            vm.onStudyExportDocumentResult(request.requestId, request.format, "content://unsupported")
            assertEquals(StudyExportResult.Failed(StudyExportFailure.UNSUPPORTED_FORMAT, partialFileMayRemain = true),
                vm.studyExportNotice.value?.result)
            assertTrue(exporter.requests.isEmpty())
            assertFalse(vm.studyExporting.value)
        }
    }
}
