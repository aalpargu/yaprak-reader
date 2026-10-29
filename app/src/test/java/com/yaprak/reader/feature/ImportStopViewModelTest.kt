package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.data.MemoryLibrary
import com.yaprak.reader.domain.backup.BackupResult
import com.yaprak.reader.domain.backup.LibraryBackup
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookExporter
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookImporter
import com.yaprak.reader.domain.library.BookRemover
import com.yaprak.reader.domain.library.BookStudyExporter
import com.yaprak.reader.domain.library.ExportResult
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.domain.library.ImportResult
import com.yaprak.reader.domain.library.StudyExportResult
import com.yaprak.reader.feature.library.BackupOperation
import com.yaprak.reader.feature.library.BookMetadataEditState
import com.yaprak.reader.feature.library.BookRemovalState
import com.yaprak.reader.feature.library.ImportNotice
import com.yaprak.reader.feature.library.ImportProgress
import com.yaprak.reader.feature.library.LibraryViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportStopViewModelTest {
    private val book = Book("1", "Existing", null, BookFormat.EPUB, "1.epub", "1.epub", 1, "1", 1)

    @Test fun `stop finishes current document and keeps competing operations blocked until its result`() = runTest {
        val completion = CompletableDeferred<ImportResult>()
        val calls = mutableListOf<String>()
        withLibrary(importer { uri -> calls += uri; completion.await() }) { vm, _, repository ->
            assertTrue(vm.prepareImport())
            vm.onImportDocumentResult(listOf("first", "second", "third"))
            runCurrent()
            assertEquals(listOf("first"), calls)
            vm.requestStopRemainingImports()
            vm.requestStopRemainingImports()
            assertEquals(ImportProgress(0, 3, stopRequested = true), vm.importProgress.value)
            assertTrue(vm.importing.value)
            assertNull(vm.notice.value)
            assertFalse(completion.isCancelled)
            assertFalse(vm.prepareImport())
            vm.importBook("competing-direct-import")
            vm.onImportDocumentResult(listOf("duplicate-picker-result"))
            assertNull(vm.prepareExport(book))
            assertNull(vm.prepareStudyExport(book))
            assertFalse(vm.prepareBackup(BackupOperation.CREATE))
            vm.removeBook(book)
            vm.saveBookMetadata(book, "Changed", "Author")
            assertEquals(BookRemovalState.Idle, vm.removal.value)
            assertEquals(BookMetadataEditState.Idle, vm.metadataEdit.value)
            assertEquals(listOf(book), repository.books.value)
            runCurrent()
            assertEquals(listOf("first"), calls)
            assertTrue(vm.importing.value)
            completion.complete(ImportResult.Added)
            runCurrent()
            val notice = requireNotNull(vm.notice.value)
            assertEquals(listOf(ImportResult.Added), notice.results)
            assertEquals(2, notice.stopped)
            assertEquals(0, notice.skipped)
            assertTrue(notice.isBatch)
            assertEquals(listOf("first"), calls)
            assertNull(vm.importProgress.value)
            assertFalse(vm.importing.value)
            assertTrue(vm.prepareImport())
        }
    }

    @Test fun `partial summary keeps earlier mixed results and actual in-flight failure`() = runTest {
        val completion = CompletableDeferred<ImportResult>()
        val calls = mutableListOf<String>()
        withLibrary(importer { uri ->
            calls += uri
            when (uri) {
                "added" -> ImportResult.Added
                "duplicate" -> ImportResult.Duplicate
                "invalid" -> ImportResult.Failed(ImportFailure.INVALID)
                else -> completion.await()
            }
        }) { vm, _, _ ->
            assertTrue(vm.prepareImport())
            vm.onImportDocumentResult(listOf("added", "duplicate", "invalid", "active", "later", "last"))
            runCurrent()
            assertEquals(ImportProgress(3, 6), vm.importProgress.value)
            vm.requestStopRemainingImports()
            completion.complete(ImportResult.Failed(ImportFailure.IO))
            runCurrent()
            val notice = requireNotNull(vm.notice.value)
            assertEquals(listOf(ImportResult.Added, ImportResult.Duplicate,
                ImportResult.Failed(ImportFailure.INVALID), ImportResult.Failed(ImportFailure.IO)), notice.results)
            assertEquals(2, notice.stopped)
            assertEquals(0, notice.skipped)
            assertEquals(listOf("added", "duplicate", "invalid", "active"), calls)
        }
    }

    @Test fun `stop before queued batch starts leaves every document unprocessed`() = runTest {
        val calls = mutableListOf<String>()
        withLibrary(importer { uri -> calls += uri; ImportResult.Added }) { vm, _, _ ->
            assertTrue(vm.prepareImport())
            vm.onImportDocumentResult(listOf("first", "second", "third"))
            vm.requestStopRemainingImports()
            assertEquals(ImportProgress(0, 3, stopRequested = true), vm.importProgress.value)
            runCurrent()
            assertTrue(calls.isEmpty())
            val notice = requireNotNull(vm.notice.value)
            assertTrue(notice.results.isEmpty())
            assertEquals(3, notice.stopped)
            assertEquals(0, notice.skipped)
            assertTrue(notice.isBatch)
            assertFalse(vm.importing.value)
            assertNull(vm.importProgress.value)
        }
    }

    @Test fun `stop during last document records its result without inventing an unprocessed remainder`() = runTest {
        val completion = CompletableDeferred<ImportResult>()
        val calls = mutableListOf<String>()
        withLibrary(importer { uri ->
            calls += uri
            if (uri == "first") ImportResult.Added else completion.await()
        }) { vm, _, _ ->
            assertTrue(vm.prepareImport())
            vm.onImportDocumentResult(listOf("first", "last"))
            runCurrent()
            assertEquals(ImportProgress(1, 2), vm.importProgress.value)
            vm.requestStopRemainingImports()
            completion.complete(ImportResult.Duplicate)
            runCurrent()
            val notice = requireNotNull(vm.notice.value)
            assertEquals(listOf(ImportResult.Added, ImportResult.Duplicate), notice.results)
            assertEquals(0, notice.stopped)
            assertEquals(0, notice.skipped)
            assertTrue(notice.isBatch)
            assertEquals(listOf("first", "last"), calls)
        }
    }

    @Test fun `idle and picker stop requests do not carry over into a new batch`() = runTest {
        val completion = CompletableDeferred<ImportResult>()
        val calls = mutableListOf<String>()
        withLibrary(importer { uri ->
            calls += uri
            if (uri == "new-first") completion.await() else ImportResult.Added
        }) { vm, _, _ ->
            vm.requestStopRemainingImports()
            assertNull(vm.importProgress.value)
            assertTrue(vm.prepareImport())
            vm.requestStopRemainingImports()
            assertTrue(vm.importPickerPending.value)
            assertNull(vm.importProgress.value)
            vm.onImportDocumentResult(listOf("old-first", "old-last"))
            assertEquals(ImportProgress(0, 2), vm.importProgress.value)
            vm.requestStopRemainingImports()
            runCurrent()
            val stoppedNotice = requireNotNull(vm.notice.value)
            assertEquals(2, stoppedNotice.stopped)
            vm.requestStopRemainingImports()
            assertSame(stoppedNotice, vm.notice.value)
            assertNull(vm.importProgress.value)
            vm.acknowledgeNotice(stoppedNotice.id)
            assertTrue(vm.prepareImport())
            vm.onImportDocumentResult(listOf("new-first", "new-last"))
            assertEquals(ImportProgress(0, 2), vm.importProgress.value)
            runCurrent()
            assertEquals(listOf("new-first"), calls)
            assertEquals(ImportProgress(0, 2), vm.importProgress.value)
            completion.complete(ImportResult.Added)
            runCurrent()
            val freshNotice = requireNotNull(vm.notice.value)
            assertEquals(listOf("new-first", "new-last"), calls)
            assertEquals(listOf(ImportResult.Added, ImportResult.Added), freshNotice.results)
            assertEquals(0, freshNotice.stopped)
            vm.requestStopRemainingImports()
            assertSame(freshNotice, vm.notice.value)
            assertNull(vm.importProgress.value)
        }
    }

    @Test fun `selection cap and user stopped remainder are counted separately`() = runTest {
        val completion = CompletableDeferred<ImportResult>()
        val calls = mutableListOf<String>()
        withLibrary(importer { uri -> calls += uri; completion.await() }) { vm, _, _ ->
            val selected = (0 until 120).map { "content://book/$it" }
            assertTrue(vm.prepareImport())
            vm.onImportDocumentResult(selected)
            runCurrent()
            assertEquals(ImportProgress(0, 100), vm.importProgress.value)
            vm.requestStopRemainingImports()
            completion.complete(ImportResult.Added)
            runCurrent()
            val notice = requireNotNull(vm.notice.value)
            assertEquals(selected.take(1), calls)
            assertEquals(listOf(ImportResult.Added), notice.results)
            assertEquals(99, notice.stopped)
            assertEquals(20, notice.skipped)
            assertEquals(120, notice.results.size + notice.stopped + notice.skipped)
        }
    }

    @Test fun `owner cancellation interrupts waiting importer and publishes no stopped summary`() = runTest {
        val completion = CompletableDeferred<ImportResult>()
        val calls = mutableListOf<String>()
        var cancellationPropagated = false
        withLibrary(importer { uri ->
            calls += uri
            try { completion.await() }
            catch (cancelled: CancellationException) { cancellationPropagated = true; throw cancelled }
        }) { vm, owner, _ ->
            assertTrue(vm.prepareImport())
            vm.onImportDocumentResult(listOf("first", "later"))
            runCurrent()
            vm.requestStopRemainingImports()
            owner.clear()
            runCurrent()
            assertTrue(cancellationPropagated)
            assertEquals(listOf("first"), calls)
            assertNull(vm.notice.value)
            assertFalse(vm.importing.value)
            assertNull(vm.importProgress.value)
        }
    }

    @Test fun `noncancellable current import cannot publish progress or summary after owner is cleared`() = runTest {
        val completion = CompletableDeferred<ImportResult>()
        val calls = mutableListOf<String>()
        var returnedAfterCommit = false
        withLibrary(importer { uri ->
            calls += uri
            val result = withContext(NonCancellable) { completion.await() }
            returnedAfterCommit = true
            result
        }) { vm, owner, _ ->
            val progressUpdates = mutableListOf<ImportProgress?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.importProgress.collect { progressUpdates += it }
            }
            assertTrue(vm.prepareImport())
            vm.onImportDocumentResult(listOf("first", "later"))
            runCurrent()
            vm.requestStopRemainingImports()
            owner.clear()
            runCurrent()
            assertFalse(returnedAfterCommit)
            completion.complete(ImportResult.Added)
            runCurrent()
            assertTrue(returnedAfterCommit)
            assertEquals(listOf("first"), calls)
            assertFalse(progressUpdates.any { it?.completed == 1 })
            assertNull(vm.notice.value)
            assertFalse(vm.importing.value)
            assertNull(vm.importProgress.value)
        }
    }

    @Test fun `a completely stopped single selection still needs the batch summary`() {
        assertTrue(ImportNotice(1, emptyList(), skipped = 0, stopped = 1).isBatch)
        assertTrue(ImportNotice(2, listOf(ImportResult.Added), skipped = 1).isBatch)
        assertTrue(ImportNotice(3, listOf(ImportResult.Added, ImportResult.Duplicate), skipped = 0).isBatch)
        assertFalse(ImportNotice(4, listOf(ImportResult.Added), skipped = 0).isBatch)
    }

    private fun importer(block: suspend (String) -> ImportResult): BookImporter = object : BookImporter {
        override suspend fun import(documentUri: String): ImportResult = block(documentUri)
    }

    private suspend fun TestScope.withLibrary(
        importer: BookImporter,
        block: suspend TestScope.(LibraryViewModel, ViewModelStore, MemoryLibrary) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val repository = MemoryLibrary().apply { books.value = listOf(book) }
            val exporter = object : BookExporter {
                override suspend fun export(bookId: String, destinationUri: String): ExportResult =
                    error("Blocked export must not run")
            }
            val backup = object : LibraryBackup {
                override suspend fun create(destinationUri: String): BackupResult = error("Blocked backup must not run")
                override suspend fun restore(sourceUri: String): BackupResult = error("Blocked restore must not run")
                override suspend fun recoverPendingRestore() = Unit
            }
            val studyExporter = object : BookStudyExporter {
                override suspend fun export(bookId: String, contentSha256: String, destinationUri: String): StudyExportResult =
                    error("Blocked study export must not run")
            }
            val vm = LibraryViewModel(repository, importer, SavedStateHandle(), dispatcher,
                exporter = exporter, remover = BookRemover { _, _ -> error("Blocked removal must not run") },
                backup = backup, studyExporter = studyExporter).also { owner.put("vm", it) }
            block(vm, owner, repository)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }
}
