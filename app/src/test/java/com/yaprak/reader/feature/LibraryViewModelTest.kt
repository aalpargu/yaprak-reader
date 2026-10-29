package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.library.LibraryLayout
import com.yaprak.reader.domain.library.LibrarySort
import com.yaprak.reader.domain.backup.*
import com.yaprak.reader.feature.library.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val book = Book("1", "İstanbul", "Deniz", BookFormat.EPUB, "1.epub", "1.epub", 1, "1", 1)
    private val importer = object : BookImporter { override suspend fun import(documentUri: String): ImportResult = error("Search must not import") }
    private class Repository(initial: List<Book>) : LibraryRepository {
        val books = MutableStateFlow(initial)
        var observations = 0
        var fail = false
        var failMetadata = false
        var metadataWrites = 0
        override fun observeBooks(): Flow<List<Book>> { observations++; return if (fail) flow { throw IOException() } else books }
        override suspend fun findById(id: String) = books.value.find { it.id == id }
        override suspend fun findByHash(sha256: String) = books.value.find { it.sha256 == sha256 }
        override suspend fun storedFileNames() = books.value.map { it.storageName }.toSet()
        override suspend fun add(book: Book) { books.value += book }
        override suspend fun updateMetadata(id: String, sha256: String, title: String, authors: String?): Boolean {
            metadataWrites++
            if (failMetadata) return false
            val before = books.value
            if (before.none { it.id == id && it.sha256 == sha256 }) return false
            books.value = before.map { if (it.id == id && it.sha256 == sha256) it.copy(title = title, authors = authors) else it }
            return true
        }
    }

    @Test fun `metadata edit trims input and updates search without changing file identity`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val repository = Repository(listOf(book))
            val vm = LibraryViewModel(repository, importer, SavedStateHandle(), dispatcher).also { owner.put("vm", it) }
            backgroundScope.launch(dispatcher) { vm.library.collect() }
            runCurrent()
            vm.saveBookMetadata(book, "  Adalar  ", "   ")
            runCurrent()
            assertTrue(vm.metadataEdit.value is BookMetadataEditState.Saved)
            assertEquals(book.copy(title = "Adalar", authors = null), repository.books.value.single())
            vm.search("Adalar")
            runCurrent()
            assertEquals(listOf("1"), (vm.library.value as LibraryState.Ready).books.map(Book::id))
            vm.clearBookMetadataState(book.id)
            assertEquals(BookMetadataEditState.Idle, vm.metadataEdit.value)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `invalid or rejected metadata edit retains original and can retry`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val repository = Repository(listOf(book))
            val vm = LibraryViewModel(repository, importer, SavedStateHandle(), dispatcher).also { owner.put("vm", it) }
            vm.saveBookMetadata(book, "  ", null)
            assertEquals(BookMetadataEditState.Failed(book.id), vm.metadataEdit.value)
            assertEquals(0, repository.metadataWrites)
            repository.failMetadata = true
            vm.saveBookMetadata(book, "Yeni", "Yazar")
            runCurrent()
            assertEquals(BookMetadataEditState.Failed(book.id), vm.metadataEdit.value)
            assertEquals(book, repository.books.value.single())
            repository.failMetadata = false
            vm.saveBookMetadata(book, "Yeni", "Yazar")
            runCurrent()
            assertTrue(vm.metadataEdit.value is BookMetadataEditState.Saved)
            assertEquals("Yeni", repository.books.value.single().title)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun `restored query filters new books without restarting observation or changing order`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val repository = Repository(listOf(book, book.copy(id = "2", title = "Other", authors = null)))
            val saved = SavedStateHandle(mapOf("libraryQuery" to "İSTANBUL"))
            val vm = LibraryViewModel(repository, importer, saved, dispatcher).also { owner.put("vm", it) }
            backgroundScope.launch(dispatcher) { vm.library.collect() }
            runCurrent()
            assertEquals(listOf("1"), (vm.library.value as LibraryState.Ready).books.map { it.id })
            assertEquals(2, (vm.library.value as LibraryState.Ready).totalCount)
            repository.add(book.copy(id = "3")); runCurrent()
            assertEquals(listOf("1", "3"), (vm.library.value as LibraryState.Ready).books.map { it.id })
            vm.search(""); runCurrent()
            assertEquals(listOf("1", "2", "3"), (vm.library.value as LibraryState.Ready).books.map { it.id })
            assertEquals(1, repository.observations)
            assertEquals("", saved.get<String>("libraryQuery"))
            vm.search("a".repeat(250)); runCurrent()
            assertEquals(200, vm.query.value.length)
            assertEquals(3, repository.books.value.size)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun `repository failure remains an error and retry keeps current query`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val repository = Repository(listOf(book)).apply { fail = true }
            val vm = LibraryViewModel(repository, importer, SavedStateHandle(), dispatcher).also { owner.put("vm", it) }
            backgroundScope.launch(dispatcher) { vm.library.collect() }
            runCurrent(); vm.search("deniz"); runCurrent()
            assertEquals(LibraryState.Error, vm.library.value)
            repository.fail = false
            vm.retry(); runCurrent()
            val ready = vm.library.value as LibraryState.Ready
            assertEquals("deniz", ready.query)
            assertEquals(listOf(book), ready.books)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `sort choice survives recreation and orders filtered new books`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val firstOwner = ViewModelStore()
        val secondOwner = ViewModelStore()
        try {
            val saved = SavedStateHandle()
            val repository = Repository(listOf(
                book.copy(id = "1", title = "Zeytin", authors = "Deniz", importedAt = 1),
                book.copy(id = "2", title = "Çınar", authors = "Deniz", importedAt = 2),
            ))
            val first = LibraryViewModel(repository, importer, saved, dispatcher)
                .also { firstOwner.put("first", it) }
            backgroundScope.launch(dispatcher) { first.library.collect() }
            first.chooseSort(LibrarySort.TITLE)
            first.search("deniz")
            runCurrent()
            assertEquals(listOf("2", "1"), (first.library.value as LibraryState.Ready).books.map { it.id })
            firstOwner.clear()

            val restored = LibraryViewModel(repository, importer, saved, dispatcher)
                .also { secondOwner.put("second", it) }
            backgroundScope.launch(dispatcher) { restored.library.collect() }
            repository.add(book.copy(id = "3", title = "Ankara", authors = "Deniz", importedAt = 3))
            runCurrent()
            assertEquals(LibrarySort.TITLE, restored.sort.value)
            assertEquals(listOf("3", "2", "1"), (restored.library.value as LibraryState.Ready).books.map { it.id })
            assertEquals(3, (restored.library.value as LibraryState.Ready).totalCount)
        } finally { firstOwner.clear(); secondOwner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `restored filter composes with search sort and new repository books`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val firstOwner = ViewModelStore()
        val secondOwner = ViewModelStore()
        try {
            val saved = SavedStateHandle()
            val repository = Repository(listOf(
                book.copy(id = "epub-zeytin", title = "Zeytin", format = BookFormat.EPUB, importedAt = 1),
                book.copy(id = "pdf-acar", title = "Acar", format = BookFormat.PDF, importedAt = 2),
                book.copy(id = "epub-cam", title = "Çam", authors = "Elif", importedAt = 3),
                book.copy(id = "epub-lale", title = "Lale", importedAt = 4, lastReadAt = 100),
            ))
            val first = LibraryViewModel(repository, importer, saved, dispatcher)
                .also { firstOwner.put("first", it) }
            backgroundScope.launch(dispatcher) { first.library.collect() }
            first.chooseFilter(LibraryFilter.EPUB)
            first.chooseSort(LibrarySort.TITLE)
            first.search("deniz")
            runCurrent()
            assertEquals(listOf("epub-lale", "epub-zeytin"),
                (first.library.value as LibraryState.Ready).books.map(Book::id))
            assertEquals("EPUB", saved.get<String>("libraryFilter"))
            firstOwner.clear()

            val restored = LibraryViewModel(repository, importer, saved, dispatcher)
                .also { secondOwner.put("second", it) }
            backgroundScope.launch(dispatcher) { restored.library.collect() }
            repository.add(book.copy(id = "epub-ankara", title = "Ankara", importedAt = 5))
            repository.add(book.copy(id = "pdf-yasemin", title = "Yasemin", format = BookFormat.PDF, importedAt = 6))
            runCurrent()
            assertEquals(LibraryFilter.EPUB, restored.filter.value)
            assertEquals(LibrarySort.TITLE, restored.sort.value)
            assertEquals("deniz", restored.query.value)
            assertEquals(listOf("epub-ankara", "epub-lale", "epub-zeytin"),
                (restored.library.value as LibraryState.Ready).books.map(Book::id))
            assertEquals(6, (restored.library.value as LibraryState.Ready).totalCount)

            restored.chooseFilter(LibraryFilter.NO_POSITION)
            runCurrent()
            assertEquals(listOf("pdf-acar", "epub-ankara", "pdf-yasemin", "epub-zeytin"),
                (restored.library.value as LibraryState.Ready).books.map(Book::id))
            assertEquals(2, repository.observations)
            assertEquals(6, repository.books.value.size)
        } finally { firstOwner.clear(); secondOwner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `unknown saved filter falls back to all without changing saved data`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val saved = SavedStateHandle(mapOf("libraryFilter" to "REMOVED_FILTER"))
            val repository = Repository(listOf(book, book.copy(id = "pdf", format = BookFormat.PDF)))
            val vm = LibraryViewModel(repository, importer, saved, dispatcher)
                .also { owner.put("vm", it) }
            backgroundScope.launch(dispatcher) { vm.library.collect() }
            runCurrent()
            assertEquals(LibraryFilter.ALL, vm.filter.value)
            assertEquals(listOf("1", "pdf"), (vm.library.value as LibraryState.Ready).books.map(Book::id))
            assertEquals("REMOVED_FILTER", saved.get<String>("libraryFilter"))
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `library layout defaults to grid and unknown saved choice falls back to grid`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        val unknownOwner = ViewModelStore()
        try {
            val fresh = LibraryViewModel(Repository(listOf(book)), importer, SavedStateHandle(), dispatcher)
                .also { owner.put("fresh", it) }
            assertEquals(LibraryLayout.GRID, fresh.layout.value)
            val saved = SavedStateHandle(mapOf("libraryLayout" to "REMOVED_LAYOUT"))
            val unknown = LibraryViewModel(Repository(listOf(book)), importer, saved, dispatcher)
                .also { unknownOwner.put("unknown", it) }
            assertEquals(LibraryLayout.GRID, unknown.layout.value)
            assertEquals("REMOVED_LAYOUT", saved.get<String>("libraryLayout"))
        } finally { owner.clear(); unknownOwner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `layout survives recreation without changing search filter sort or repository results`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val firstOwner = ViewModelStore()
        val secondOwner = ViewModelStore()
        try {
            val saved = SavedStateHandle()
            val repository = Repository(listOf(
                book.copy(id = "z", title = "Zeytin", importedAt = 1),
                book.copy(id = "p", title = "Acar", format = BookFormat.PDF, importedAt = 2),
                book.copy(id = "c", title = "Çınar", importedAt = 3),
            ))
            val first = LibraryViewModel(repository, importer, saved, dispatcher)
                .also { firstOwner.put("first", it) }
            backgroundScope.launch(dispatcher) { first.library.collect() }
            first.search("deniz")
            first.chooseFilter(LibraryFilter.EPUB)
            first.chooseSort(LibrarySort.TITLE)
            runCurrent()
            val before = first.library.value as LibraryState.Ready
            assertEquals(listOf("c", "z"), before.books.map(Book::id))
            first.chooseLayout(LibraryLayout.LIST)
            runCurrent()
            assertEquals(LibraryLayout.LIST, first.layout.value)
            assertEquals(before, first.library.value)
            assertEquals(1, repository.observations)
            firstOwner.clear()

            val restored = LibraryViewModel(repository, importer, saved, dispatcher)
                .also { secondOwner.put("second", it) }
            backgroundScope.launch(dispatcher) { restored.library.collect() }
            runCurrent()
            assertEquals(LibraryLayout.LIST, restored.layout.value)
            assertEquals("LIST", saved.get<String>("libraryLayout"))
            assertEquals("deniz", restored.query.value)
            assertEquals(LibraryFilter.EPUB, restored.filter.value)
            assertEquals(LibrarySort.TITLE, restored.sort.value)
            assertEquals(before, restored.library.value)
            repository.add(book.copy(id = "a", title = "Ankara", importedAt = 4))
            runCurrent()
            val updated = restored.library.value as LibraryState.Ready
            assertEquals(listOf("a", "c", "z"), updated.books.map(Book::id))
            assertEquals(4, updated.totalCount)
            restored.chooseLayout(LibraryLayout.GRID)
            runCurrent()
            assertEquals(LibraryLayout.GRID, restored.layout.value)
            assertEquals(updated, restored.library.value)
            assertEquals(2, repository.observations)
        } finally { firstOwner.clear(); secondOwner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `export result uses reserved book identity and one destination only`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val calls = mutableListOf<Pair<String, String>>()
            val exporter = object : BookExporter {
                override suspend fun export(bookId: String, destinationUri: String): ExportResult {
                    calls += bookId to destinationUri
                    return ExportResult.Exported
                }
            }
            val vm = LibraryViewModel(Repository(listOf(book)), importer, SavedStateHandle(), dispatcher, exporter)
                .also { owner.put("vm", it) }
            assertEquals(BookFormat.EPUB, vm.prepareExport(book)?.format)
            assertNull(vm.prepareExport(book.copy(id = "other")))
            vm.onExportDocumentResult(BookFormat.PDF, "content://wrong-type")
            assertTrue(calls.isEmpty())
            vm.onExportDocumentResult(BookFormat.EPUB, "content://created")
            runCurrent()
            vm.onExportDocumentResult(BookFormat.EPUB, "content://duplicate")
            assertEquals(listOf("1" to "content://created"), calls)
            assertEquals(ExportResult.Exported, vm.exportNotice.value?.result)
            assertFalse(vm.exporting.value)
            assertFalse(vm.exportPickerPending.value)
            val id = requireNotNull(vm.exportNotice.value).id
            vm.acknowledgeExportNotice(id + 1)
            assertNotNull(vm.exportNotice.value)
            vm.acknowledgeExportNotice(id)
            assertNull(vm.exportNotice.value)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `picker cancellation never invokes exporter and a new request can start`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val exporter = object : BookExporter {
                override suspend fun export(bookId: String, destinationUri: String): ExportResult =
                    error("Cancellation must not write")
            }
            val vm = LibraryViewModel(Repository(listOf(book)), importer, SavedStateHandle(), dispatcher, exporter)
                .also { owner.put("vm", it) }
            assertNotNull(vm.prepareExport(book))
            vm.onExportDocumentResult(BookFormat.EPUB, null)
            runCurrent()
            assertNull(vm.exportNotice.value)
            assertFalse(vm.exporting.value)
            assertNotNull(vm.prepareExport(book))
            vm.onExportLaunchFailed(BookFormat.EPUB)
            assertEquals(ExportResult.Failed(ExportFailure.ACCESS), vm.exportNotice.value?.result)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `pending export survives ViewModel recreation and preserves failure detail`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val firstOwner = ViewModelStore()
        val secondOwner = ViewModelStore()
        try {
            val saved = SavedStateHandle()
            val calls = mutableListOf<String>()
            val exporter = object : BookExporter {
                override suspend fun export(bookId: String, destinationUri: String): ExportResult {
                    calls += bookId
                    return ExportResult.Failed(ExportFailure.IO, partialFileMayRemain = true)
                }
            }
            val first = LibraryViewModel(Repository(listOf(book)), importer, saved, dispatcher, exporter)
                .also { firstOwner.put("vm", it) }
            assertNotNull(first.prepareExport(book))
            firstOwner.clear()
            val restored = LibraryViewModel(Repository(listOf(book)), importer, saved, dispatcher, exporter)
                .also { secondOwner.put("vm", it) }
            assertTrue(restored.exportPickerPending.value)
            assertNull(restored.prepareExport(book.copy(id = "another")))
            restored.onExportDocumentResult(BookFormat.EPUB, "content://created")
            runCurrent()
            assertEquals(listOf("1"), calls)
            assertEquals(ExportResult.Failed(ExportFailure.IO, true), restored.exportNotice.value?.result)
        } finally { firstOwner.clear(); secondOwner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `active export blocks another picker until completion`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val completed = CompletableDeferred<ExportResult>()
            val exporter = object : BookExporter {
                override suspend fun export(bookId: String, destinationUri: String): ExportResult = completed.await()
            }
            val vm = LibraryViewModel(Repository(listOf(book)), importer, SavedStateHandle(), dispatcher, exporter)
                .also { owner.put("vm", it) }
            assertNotNull(vm.prepareExport(book))
            vm.onExportDocumentResult(BookFormat.EPUB, "content://created")
            runCurrent()
            assertTrue(vm.exporting.value)
            assertNull(vm.prepareExport(book.copy(id = "another")))
            completed.complete(ExportResult.Exported)
            runCurrent()
            assertFalse(vm.exporting.value)
            assertNotNull(vm.prepareExport(book))
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `removal preserves exact selected identity and reports failure for retry`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val calls = mutableListOf<Pair<String, String>>()
            var result: BookRemovalResult = BookRemovalResult.Failed
            val remover = BookRemover { id, hash -> calls += id to hash; result }
            val vm = LibraryViewModel(Repository(listOf(book)), importer, SavedStateHandle(), dispatcher,
                remover = remover).also { owner.put("vm", it) }
            vm.removeBook(book)
            runCurrent()
            assertEquals(listOf(book.id to book.sha256), calls)
            assertEquals(BookRemovalState.Error(book.id, BookRemovalResult.Failed), vm.removal.value)
            vm.clearRemovalError("another")
            assertTrue(vm.removal.value is BookRemovalState.Error)
            vm.clearRemovalError(book.id)
            assertEquals(BookRemovalState.Idle, vm.removal.value)
            result = BookRemovalResult.Removed(privateFileMayRemain = true)
            vm.removeBook(book)
            runCurrent()
            assertEquals(BookRemovalResult.Removed(true), vm.removalNotice.value?.result)
            assertEquals(BookRemovalState.Idle, vm.removal.value)
            val id = requireNotNull(vm.removalNotice.value).id
            vm.acknowledgeRemovalNotice(id)
            assertNull(vm.removalNotice.value)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `one in-flight removal blocks another removal and export`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val completion = CompletableDeferred<BookRemovalResult>()
            val calls = mutableListOf<String>()
            val remover = BookRemover { id, _ -> calls += id; completion.await() }
            val exporter = object : BookExporter {
                override suspend fun export(bookId: String, destinationUri: String): ExportResult = ExportResult.Exported
            }
            val vm = LibraryViewModel(Repository(listOf(book)), importer, SavedStateHandle(), dispatcher,
                exporter, remover).also { owner.put("vm", it) }
            vm.removeBook(book)
            runCurrent()
            vm.removeBook(book.copy(id = "other"))
            assertEquals(listOf(book.id), calls)
            assertNull(vm.prepareExport(book))
            completion.complete(BookRemovalResult.InUse)
            runCurrent()
            assertEquals(BookRemovalState.Error(book.id, BookRemovalResult.InUse), vm.removal.value)
            assertNotNull(vm.prepareExport(book))
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `backup picker survives recreation and rejects mismatched or duplicate result`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val firstOwner = ViewModelStore()
        val secondOwner = ViewModelStore()
        try {
            val saved = SavedStateHandle()
            val calls = mutableListOf<String>()
            val backup = object : LibraryBackup {
                override suspend fun create(destinationUri: String): BackupResult {
                    calls += destinationUri
                    return BackupResult.Failed(BackupFailure.IO, partialFileMayRemain = true)
                }
                override suspend fun restore(sourceUri: String): BackupResult = error("Wrong operation")
                override suspend fun recoverPendingRestore() = Unit
            }
            val first = LibraryViewModel(Repository(listOf(book)), importer, saved, dispatcher,
                backup = backup).also { firstOwner.put("vm", it) }
            assertTrue(first.prepareBackup(BackupOperation.CREATE))
            assertFalse(first.prepareBackup(BackupOperation.RESTORE))
            firstOwner.clear()
            val restored = LibraryViewModel(Repository(listOf(book)), importer, saved, dispatcher,
                backup = backup).also { secondOwner.put("vm", it) }
            assertTrue(restored.backupPickerPending.value)
            restored.onBackupDocumentResult(BackupOperation.RESTORE, "content://wrong")
            assertTrue(calls.isEmpty())
            restored.onBackupDocumentResult(BackupOperation.CREATE, "content://backup")
            runCurrent()
            restored.onBackupDocumentResult(BackupOperation.CREATE, "content://again")
            assertEquals(listOf("content://backup"), calls)
            assertEquals(BackupResult.Failed(BackupFailure.IO, true), restored.backupNotice.value?.result)
            assertFalse(restored.backupPickerPending.value)
            val id = requireNotNull(restored.backupNotice.value).id
            restored.acknowledgeBackupNotice(id)
            assertNull(restored.backupNotice.value)
        } finally { firstOwner.clear(); secondOwner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `restore needs an empty library and running backup blocks library mutations`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val repository = Repository(listOf(book))
            val completion = CompletableDeferred<BackupResult>()
            val backup = object : LibraryBackup {
                override suspend fun create(destinationUri: String): BackupResult = completion.await()
                override suspend fun restore(sourceUri: String): BackupResult = error("Nonempty library")
                override suspend fun recoverPendingRestore() = Unit
            }
            val vm = LibraryViewModel(repository, importer, SavedStateHandle(), dispatcher,
                remover = BookRemover { _, _ -> error("Removal must be blocked") }, backup = backup)
                .also { owner.put("vm", it) }
            backgroundScope.launch(dispatcher) { vm.library.collect() }
            runCurrent()
            assertFalse(vm.prepareBackup(BackupOperation.RESTORE))
            assertEquals(BackupResult.Failed(BackupFailure.NOT_EMPTY), vm.backupNotice.value?.result)
            assertTrue(vm.prepareBackup(BackupOperation.CREATE))
            vm.onBackupDocumentResult(BackupOperation.CREATE, "content://backup")
            runCurrent()
            assertEquals(BackupOperation.CREATE, vm.backupRunning.value)
            assertFalse(vm.prepareBackup(BackupOperation.CREATE))
            vm.importBook("content://book")
            vm.removeBook(book)
            assertFalse(vm.importing.value)
            assertEquals(BookRemovalState.Idle, vm.removal.value)
            completion.complete(BackupResult.Created(1))
            runCurrent()
            assertNull(vm.backupRunning.value)
            assertEquals(BackupResult.Created(1), vm.backupNotice.value?.result)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `cancelled backup picker does not call engine and may be retried`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val backup = object : LibraryBackup {
                override suspend fun create(destinationUri: String): BackupResult = error("Cancelled")
                override suspend fun restore(sourceUri: String): BackupResult = error("Cancelled")
                override suspend fun recoverPendingRestore() = Unit
            }
            val vm = LibraryViewModel(Repository(emptyList()), importer, SavedStateHandle(), dispatcher,
                backup = backup).also { owner.put("vm", it) }
            assertTrue(vm.prepareBackup(BackupOperation.CREATE))
            vm.onBackupDocumentResult(BackupOperation.CREATE, null)
            assertNull(vm.backupNotice.value)
            assertFalse(vm.backupPickerPending.value)
            assertTrue(vm.prepareBackup(BackupOperation.CREATE))
            vm.onBackupLaunchFailed(BackupOperation.CREATE)
            assertEquals(BackupResult.Failed(BackupFailure.ACCESS), vm.backupNotice.value?.result)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `batch import keeps result order and finishes after each selected document`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val first = CompletableDeferred<ImportResult>()
            val calls = mutableListOf<String>()
            val batchImporter = object : BookImporter {
                override suspend fun import(documentUri: String): ImportResult {
                    calls += documentUri
                    return when (documentUri) {
                        "first" -> first.await()
                        "second" -> ImportResult.Duplicate
                        "third" -> throw IOException("Provider disappeared")
                        else -> ImportResult.Failed(ImportFailure.UNSUPPORTED)
                    }
                }
            }
            val vm = LibraryViewModel(Repository(emptyList()), batchImporter, SavedStateHandle(), dispatcher)
                .also { owner.put("vm", it) }
            assertTrue(vm.prepareImport())
            vm.onImportDocumentResult(listOf("first", "second", "third", "fourth"))
            assertFalse(vm.importPickerPending.value)
            assertEquals(ImportProgress(0, 4), vm.importProgress.value)
            runCurrent()
            assertEquals(listOf("first"), calls)
            assertNull(vm.notice.value)
            first.complete(ImportResult.Added)
            runCurrent()
            assertEquals(listOf("first", "second", "third", "fourth"), calls)
            assertEquals(listOf(
                ImportResult.Added,
                ImportResult.Duplicate,
                ImportResult.Failed(ImportFailure.UNEXPECTED),
                ImportResult.Failed(ImportFailure.UNSUPPORTED),
            ), vm.notice.value?.results)
            assertEquals(0, vm.notice.value?.skipped)
            assertNull(vm.importProgress.value)
            assertFalse(vm.importing.value)
            val id = requireNotNull(vm.notice.value).id
            vm.acknowledgeNotice(id)
            assertNull(vm.notice.value)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `batch import processes at most one hundred documents and ignores duplicate picker result`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val calls = mutableListOf<String>()
            val batchImporter = object : BookImporter {
                override suspend fun import(documentUri: String): ImportResult {
                    calls += documentUri
                    return ImportResult.Added
                }
            }
            val vm = LibraryViewModel(Repository(emptyList()), batchImporter, SavedStateHandle(), dispatcher)
                .also { owner.put("vm", it) }
            assertTrue(vm.prepareImport())
            val uris = (0 until 103).map { "content://book/$it" }
            vm.onImportDocumentResult(uris)
            runCurrent()
            vm.onImportDocumentResult(listOf("content://duplicate-callback"))
            assertEquals(uris.take(100), calls)
            assertEquals(100, vm.notice.value?.results?.size)
            assertTrue(vm.notice.value?.results?.all { it == ImportResult.Added } == true)
            assertEquals(3, vm.notice.value?.skipped)
            assertFalse(vm.importing.value)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `cancelled batch stops before later files and publishes no partial success notice`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val calls = mutableListOf<String>()
            val batchImporter = object : BookImporter {
                override suspend fun import(documentUri: String): ImportResult {
                    calls += documentUri
                    if (documentUri == "second") throw CancellationException("Picker owner was cleared")
                    return ImportResult.Added
                }
            }
            val vm = LibraryViewModel(Repository(emptyList()), batchImporter, SavedStateHandle(), dispatcher)
                .also { owner.put("vm", it) }
            assertTrue(vm.prepareImport())
            vm.onImportDocumentResult(listOf("first", "second", "third"))
            runCurrent()
            assertEquals(listOf("first", "second"), calls)
            assertNull(vm.notice.value)
            assertNull(vm.importProgress.value)
            assertFalse(vm.importing.value)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `pending import reservation survives recreation and blocks competing operations`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val firstOwner = ViewModelStore()
        val secondOwner = ViewModelStore()
        try {
            val saved = SavedStateHandle()
            val calls = mutableListOf<String>()
            val batchImporter = object : BookImporter {
                override suspend fun import(documentUri: String): ImportResult {
                    calls += documentUri
                    return ImportResult.Added
                }
            }
            val exporter = object : BookExporter {
                override suspend fun export(bookId: String, destinationUri: String): ExportResult = ExportResult.Exported
            }
            val backup = object : LibraryBackup {
                override suspend fun create(destinationUri: String): BackupResult = BackupResult.Created(0)
                override suspend fun restore(sourceUri: String): BackupResult = BackupResult.Restored(0)
                override suspend fun recoverPendingRestore() = Unit
            }
            val remover = BookRemover { _, _ -> error("Removal must be blocked") }
            val first = LibraryViewModel(Repository(listOf(book)), batchImporter, saved, dispatcher,
                exporter, remover, backup).also { firstOwner.put("vm", it) }
            assertTrue(first.prepareImport())
            assertFalse(first.prepareImport())
            firstOwner.clear()
            val restored = LibraryViewModel(Repository(listOf(book)), batchImporter, saved, dispatcher,
                exporter, remover, backup).also { secondOwner.put("vm", it) }
            assertTrue(restored.importPickerPending.value)
            assertFalse(restored.prepareImport())
            assertNull(restored.prepareExport(book))
            assertFalse(restored.prepareBackup(BackupOperation.CREATE))
            restored.removeBook(book)
            restored.importBook("content://bypass")
            assertEquals(BookRemovalState.Idle, restored.removal.value)
            assertTrue(calls.isEmpty())
            restored.onImportDocumentResult(emptyList())
            assertFalse(restored.importPickerPending.value)
            assertNull(restored.notice.value)
            assertTrue(restored.prepareImport())
            restored.onImportLaunchFailed()
            assertEquals(listOf(ImportResult.Failed(ImportFailure.ACCESS)), restored.notice.value?.results)
            assertFalse(restored.importPickerPending.value)
            assertTrue(calls.isEmpty())
        } finally { firstOwner.clear(); secondOwner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `active batch blocks other operations until importer completes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val completion = CompletableDeferred<ImportResult>()
            val batchImporter = object : BookImporter {
                override suspend fun import(documentUri: String): ImportResult =
                    if (documentUri == "content://first") ImportResult.Added else completion.await()
            }
            val exporter = object : BookExporter {
                override suspend fun export(bookId: String, destinationUri: String): ExportResult = ExportResult.Exported
            }
            val backup = object : LibraryBackup {
                override suspend fun create(destinationUri: String): BackupResult = BackupResult.Created(0)
                override suspend fun restore(sourceUri: String): BackupResult = BackupResult.Restored(0)
                override suspend fun recoverPendingRestore() = Unit
            }
            val vm = LibraryViewModel(Repository(listOf(book)), batchImporter, SavedStateHandle(), dispatcher,
                exporter, BookRemover { _, _ -> error("Removal must be blocked") }, backup)
                .also { owner.put("vm", it) }
            assertTrue(vm.prepareImport())
            vm.onImportDocumentResult(listOf("content://first", "content://second"))
            runCurrent()
            assertTrue(vm.importing.value)
            assertEquals(ImportProgress(1, 2), vm.importProgress.value)
            assertFalse(vm.prepareImport())
            assertNull(vm.prepareExport(book))
            assertFalse(vm.prepareBackup(BackupOperation.CREATE))
            vm.removeBook(book)
            assertEquals(BookRemovalState.Idle, vm.removal.value)
            completion.complete(ImportResult.Added)
            runCurrent()
            assertFalse(vm.importing.value)
            assertEquals(listOf(ImportResult.Added, ImportResult.Added), vm.notice.value?.results)
            assertTrue(vm.prepareImport())
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
}
