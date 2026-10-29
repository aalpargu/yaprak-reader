package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.backup.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.library.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryDiscoveryTest {
    private val book = Book("one", "Bir Kitap", "Deniz", BookFormat.EPUB,
        "one.epub", "one.epub", 30, "a".repeat(64), 1)

    @Test fun `restored picker consumes shared nonce exactly once and never persists source URI`() = modelTest {
        val saved = SavedStateHandle()
        val scanner = Scanner()
        val firstOwner = ViewModelStore()
        val restoredOwner = ViewModelStore()
        try {
            val first = model(Fixture(), scanner, saved, firstOwner)
            runCurrent()
            assertTrue(first.prepareDiscovery())
            val reservation = saved.get<String>("pendingDiscoveryRequestId")!!
            firstOwner.clear()
            val restored = model(Fixture(), scanner, saved, restoredOwner)
            assertEquals(DiscoveryState.Choosing(reservation), restored.discoveryState.value)
            restored.onDiscoveryTreeResult("content://books/tree/selected")
            restored.onDiscoveryTreeResult("content://books/tree/duplicate")
            first.onDiscoveryTreeResult("content://books/tree/old-activity")
            runCurrent()
            assertEquals(listOf("content://books/tree/selected"), scanner.requests)
            assertTrue((restored.discoveryState.value as DiscoveryState.Preview).selectedIds.isEmpty())
            assertFalse(saved.keys().contains("pendingDiscoveryRequestId"))
            assertFalse(saved.keys().any { saved.get<Any>(it)?.toString()?.contains("content://") == true })
        } finally { firstOwner.clear(); restoredOwner.clear() }
    }

    @Test fun `late old controller cannot consume or clear a newer picker reservation`() = modelTest {
        val saved = SavedStateHandle()
        val scanner = Scanner()
        val firstOwner = ViewModelStore()
        val restoredOwner = ViewModelStore()
        try {
            val first = model(Fixture(), scanner, saved, firstOwner)
            assertTrue(first.prepareDiscovery())
            val oldId = (first.discoveryState.value as DiscoveryState.Choosing).sessionId
            firstOwner.clear()
            val restored = model(Fixture(), scanner, saved, restoredOwner)
            restored.onDiscoveryTreeResult(null)
            assertTrue(restored.prepareDiscovery())
            val newId = (restored.discoveryState.value as DiscoveryState.Choosing).sessionId
            assertNotEquals(oldId, newId)
            first.onDiscoveryTreeResult("content://late-old-result")
            first.onDiscoveryLaunchFailed()
            first.dismissDiscovery(oldId)
            assertEquals(newId, saved.get<String>("pendingDiscoveryRequestId"))
            assertEquals(DiscoveryState.Choosing(newId), restored.discoveryState.value)
            restored.onDiscoveryTreeResult("content://new-result")
            runCurrent()
            assertEquals(listOf("content://new-result"), scanner.requests)
        } finally { firstOwner.clear(); restoredOwner.clear() }
    }

    @Test fun `cancelled picker and failed launch do not scan or import`() = modelTest {
        val fixture = Fixture()
        val scanner = Scanner()
        val owner = ViewModelStore()
        try {
            val model = model(fixture, scanner, SavedStateHandle(), owner)
            assertTrue(model.prepareDiscovery())
            model.onDiscoveryTreeResult(null)
            assertEquals(DiscoveryState.Idle, model.discoveryState.value)
            assertTrue(model.prepareDiscovery())
            model.onDiscoveryLaunchFailed()
            val failed = model.discoveryState.value as DiscoveryState.Failed
            assertEquals(DiscoveryFailure.ACCESS, failed.reason)
            assertFalse(model.prepareDiscovery())
            model.dismissDiscovery(failed.sessionId)
            assertTrue(scanner.requests.isEmpty())
            assertTrue(fixture.imported.isEmpty())
            assertTrue(model.prepareImport())
            model.onImportDocumentResult(emptyList())
        } finally { owner.clear() }
    }

    @Test fun `process recreation drops preview selections and never restarts scan or imports`() = modelTest {
        val fixture = Fixture()
        val scanner = Scanner()
        val saved = SavedStateHandle()
        val firstOwner = ViewModelStore()
        val restoredOwner = ViewModelStore()
        try {
            val first = model(fixture, scanner, saved, firstOwner)
            assertTrue(first.prepareDiscovery())
            first.onDiscoveryTreeResult("content://tree")
            val preview = first.discoveryState.value as DiscoveryState.Preview
            first.selectAllDiscoveredBooks(preview.sessionId)
            assertEquals(setOf("candidate-0"), (first.discoveryState.value as DiscoveryState.Preview).selectedIds)
            firstOwner.clear()
            val restored = model(fixture, scanner, saved, restoredOwner)
            assertEquals(DiscoveryState.Idle, restored.discoveryState.value)
            restored.importDiscoveredBooks(preview.sessionId)
            runCurrent()
            assertEquals(listOf("content://tree"), scanner.requests)
            assertTrue(fixture.imported.isEmpty())
        } finally { firstOwner.clear(); restoredOwner.clear() }
    }

    @Test fun `selection starts empty is capped at100 and select all replaces first matching100`() = modelTest {
        val candidates = (0 until 1000).map { candidate(it, if (it < 150) "İSTANBUL Atlas $it.pdf" else "Başka Kitap $it.epub") }
        val scanner = Scanner().apply { result = { DiscoveryResult.Complete(candidates, 1100) } }
        val owner = ViewModelStore()
        try {
            val model = model(Fixture(), scanner, SavedStateHandle(), owner)
            model.prepareDiscovery(); model.onDiscoveryTreeResult("content://tree")
            val id = (model.discoveryState.value as DiscoveryState.Preview).sessionId
            assertTrue((model.discoveryState.value as DiscoveryState.Preview).selectedIds.isEmpty())
            model.importDiscoveredBooks(id)
            for (index in 800..900) model.selectDiscoveredBook(id, "candidate-$index", true)
            assertEquals((800 until 900).map { "candidate-$it" }.toSet(), (model.discoveryState.value as DiscoveryState.Preview).selectedIds)
            model.selectDiscoveredBook(id, "missing", true)
            model.searchDiscovery(id, "istanbul atlas")
            model.selectAllDiscoveredBooks(id)
            assertEquals((0 until 100).map { "candidate-$it" }.toSet(), (model.discoveryState.value as DiscoveryState.Preview).selectedIds)
            model.searchDiscovery(id, "Başka")
            assertEquals(100, (model.discoveryState.value as DiscoveryState.Preview).selectedIds.size)
            model.clearDiscoveredSelection(id)
            assertTrue((model.discoveryState.value as DiscoveryState.Preview).selectedIds.isEmpty())
            model.searchDiscovery(id, "x".repeat(300))
            assertEquals(200, (model.discoveryState.value as DiscoveryState.Preview).query.length)
            model.selectAllDiscoveredBooks(id)
            assertTrue((model.discoveryState.value as DiscoveryState.Preview).selectedIds.isEmpty())
        } finally { owner.clear() }
    }

    @Test fun `selected current source URIs enter existing ordered import queue once`() = modelTest {
        val fixture = Fixture()
        val release = CompletableDeferred<Unit>()
        fixture.importResult = { uri -> if (uri == "content://candidate/2") release.await(); ImportResult.Added }
        val scanner = Scanner().apply { result = { DiscoveryResult.Complete(listOf(candidate(2), candidate(1), candidate(3)), 3) } }
        val owner = ViewModelStore()
        try {
            val model = model(fixture, scanner, SavedStateHandle(), owner)
            model.prepareDiscovery(); model.onDiscoveryTreeResult("content://tree")
            val id = (model.discoveryState.value as DiscoveryState.Preview).sessionId
            model.selectDiscoveredBook(id, "candidate-1", true)
            model.selectDiscoveredBook(id, "candidate-2", true)
            model.searchDiscovery(id, "No results") // Filtering does not silently lose checked books.
            model.importDiscoveredBooks(id)
            model.importDiscoveredBooks(id)
            assertEquals(DiscoveryState.Idle, model.discoveryState.value)
            assertTrue(model.importing.value)
            assertFalse(model.prepareDiscovery())
            assertEquals(listOf("content://candidate/2"), fixture.imported)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("content://candidate/2", "content://candidate/1"), fixture.imported)
            assertEquals(listOf(ImportResult.Added, ImportResult.Added), model.notice.value!!.results)
            assertFalse(model.importing.value)
        } finally { owner.clear() }
    }

    @Test fun `stale preview events cannot select dismiss cancel or import a newer result`() = modelTest {
        val fixture = Fixture()
        val scanner = Scanner()
        val owner = ViewModelStore()
        try {
            val model = model(fixture, scanner, SavedStateHandle(), owner)
            model.prepareDiscovery(); model.onDiscoveryTreeResult("content://old")
            val oldId = (model.discoveryState.value as DiscoveryState.Preview).sessionId
            model.selectAllDiscoveredBooks(oldId)
            model.dismissDiscovery(oldId)
            model.prepareDiscovery(); model.onDiscoveryTreeResult("content://new")
            val newId = (model.discoveryState.value as DiscoveryState.Preview).sessionId
            model.selectAllDiscoveredBooks(newId)
            val current = model.discoveryState.value
            model.searchDiscovery(oldId, "wrong")
            model.selectDiscoveredBook(oldId, "candidate-0", false)
            model.selectAllDiscoveredBooks(oldId)
            model.clearDiscoveredSelection(oldId)
            model.importDiscoveredBooks(oldId)
            model.cancelDiscovery(oldId)
            model.dismissDiscovery(oldId)
            assertEquals(current, model.discoveryState.value)
            assertTrue(fixture.imported.isEmpty())
            model.importDiscoveredBooks(newId)
            assertEquals(listOf("content://candidate/0"), fixture.imported)
        } finally { owner.clear() }
    }

    @Test fun `cancel reserves all mutation gates until actual provider cleanup ends`() = modelTest {
        val fixture = Fixture()
        val cleanup = CompletableDeferred<Unit>()
        var closing = false
        val scanner = Scanner().apply { result = {
            try { awaitCancellation() } finally { closing = true; withContext(NonCancellable) { cleanup.await() } }
        } }
        val owner = ViewModelStore()
        try {
            val model = model(fixture, scanner, SavedStateHandle(), owner)
            runCurrent()
            model.prepareDiscovery(); model.onDiscoveryTreeResult("content://tree")
            val id = (model.discoveryState.value as DiscoveryState.Scanning).sessionId
            model.cancelDiscovery(id)
            assertTrue(closing)
            assertTrue((model.discoveryState.value as DiscoveryState.Scanning).cancelRequested)
            assertBlocked(model, fixture)
            model.dismissDiscovery(id)
            assertTrue(model.discoveryState.value is DiscoveryState.Scanning)
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(DiscoveryState.Idle, model.discoveryState.value)
            assertNull(model.notice.value)
            assertTrue(model.prepareImport())
            model.onImportDocumentResult(emptyList())
        } finally { cleanup.complete(Unit); owner.clear() }
    }

    @Test fun `choosing scanning preview and failed states block all library mutations`() = modelTest {
        val fixture = Fixture()
        val release = CompletableDeferred<DiscoveryResult>()
        val scanner = Scanner().apply { result = { release.await() } }
        val owner = ViewModelStore()
        try {
            val model = model(fixture, scanner, SavedStateHandle(), owner)
            runCurrent()
            model.prepareDiscovery()
            assertBlocked(model, fixture)
            model.onDiscoveryTreeResult("content://tree")
            assertBlocked(model, fixture)
            release.complete(DiscoveryResult.Truncated(listOf(candidate(0)), 20, DiscoveryLimit.DEPTH))
            runCurrent()
            val preview = model.discoveryState.value as DiscoveryState.Preview
            assertEquals(DiscoveryLimit.DEPTH, preview.limit)
            assertBlocked(model, fixture)
            model.dismissDiscovery(preview.sessionId)
            scanner.result = { DiscoveryResult.Failed(DiscoveryFailure.IO) }
            assertTrue(model.prepareDiscovery())
            model.onDiscoveryTreeResult("content://failed")
            assertBlocked(model, fixture)
            assertEquals(DiscoveryFailure.IO, (model.discoveryState.value as DiscoveryState.Failed).reason)
        } finally { owner.clear() }
    }

    @Test fun `existing picker reservations block discovery until released`() = modelTest {
        val fixture = Fixture()
        val owner = ViewModelStore()
        try {
            val model = model(fixture, Scanner(), SavedStateHandle(), owner)
            runCurrent()
            assertTrue(model.prepareImport()); assertFalse(model.prepareDiscovery())
            model.onImportDocumentResult(emptyList())
            assertNotNull(model.prepareExport(book)); assertFalse(model.prepareDiscovery())
            model.onExportDocumentResult(book.format, null)
            assertNotNull(model.prepareStudyExport(book)); assertFalse(model.prepareDiscovery())
            model.onStudyExportDocumentResult(null)
            assertTrue(model.prepareBackup(BackupOperation.CREATE)); assertFalse(model.prepareDiscovery())
            model.onBackupDocumentResult(BackupOperation.CREATE, null)
            assertTrue(model.prepareCoverChange(book)); assertFalse(model.prepareDiscovery())
            model.onCoverDocumentResult(null)
            assertTrue(model.prepareDiscovery())
        } finally { owner.clear() }
    }

    @Test fun `invalid restoration absent scanner and unexpected failure never claim discovery success`() = modelTest {
        val fixture = Fixture()
        val invalid = SavedStateHandle(mapOf("pendingDiscoveryRequestId" to "not-a-uuid"))
        val owner = ViewModelStore()
        try {
            val scanner = Scanner().apply { result = { throw IllegalStateException("provider failure") } }
            val model = model(fixture, scanner, invalid, owner)
            assertEquals(DiscoveryState.Idle, model.discoveryState.value)
            assertFalse(invalid.keys().contains("pendingDiscoveryRequestId"))
            model.prepareDiscovery(); model.onDiscoveryTreeResult("content://tree")
            assertEquals(DiscoveryFailure.UNEXPECTED, (model.discoveryState.value as DiscoveryState.Failed).reason)
            val absent = LibraryViewModel(fixture, fixture, SavedStateHandle(), UnconfinedTestDispatcher(testScheduler))
            owner.put("absent", absent)
            assertFalse(absent.discoveryAvailable)
            assertFalse(absent.prepareDiscovery())
            absent.onDiscoveryTreeResult("content://ignored")
            assertEquals(DiscoveryState.Idle, absent.discoveryState.value)
            assertTrue(fixture.imported.isEmpty())
        } finally { owner.clear() }
    }

    @Test fun `malformed duplicate and oversized scan output is rejected before any import`() = modelTest {
        for (result in listOf(
            DiscoveryResult.Complete(listOf(candidate(0), candidate(0)), 2),
            DiscoveryResult.Complete(listOf(candidate(0).copy(documentUri = "")), 1),
            DiscoveryResult.Complete((0..1000).map { candidate(it) }, 1001),
            DiscoveryResult.Complete(listOf(candidate(0)), -1),
        )) {
            val fixture = Fixture()
            val owner = ViewModelStore()
            try {
                val model = model(fixture, Scanner().apply { this.result = { result } }, SavedStateHandle(), owner)
                model.prepareDiscovery(); model.onDiscoveryTreeResult("content://tree")
                val failed = model.discoveryState.value as DiscoveryState.Failed
                assertEquals(DiscoveryFailure.UNEXPECTED, failed.reason)
                model.selectAllDiscoveredBooks(failed.sessionId)
                model.importDiscoveredBooks(failed.sessionId)
                assertTrue(fixture.imported.isEmpty())
            } finally { owner.clear() }
        }
    }

    @Test fun `remembered tree is rescanned through actual discovery session without saving source or importing`() = modelTest {
        val fixture = Fixture(); val scanner = Scanner(); val owner = ViewModelStore(); val saved = SavedStateHandle()
        val tree = "content://books.provider/tree/primary%3ABooks"
        val snapshot = BookFolderSnapshot(java.util.UUID.randomUUID().toString(), SavedBookFolder(tree, "Kitaplar"))
        val folders = FolderFixture(snapshot)
        try {
            val model = model(fixture, scanner, saved, owner, folders)
            runCurrent(); model.bindBookFolderOwner("route", true)
            model.scanBookFolder("route", snapshot); runCurrent()
            val preview = model.discoveryState.value as DiscoveryState.Preview
            assertEquals(tree, preview.treeUri)
            assertEquals(listOf(tree), scanner.requests)
            assertTrue(preview.selectedIds.isEmpty()); assertTrue(fixture.imported.isEmpty())
            assertTrue(saved.keys().none { key -> saved.get<Any>(key)?.toString()?.contains("content://") == true })
            assertFalse(model.prepareImport())
            model.selectDiscoveredBook(preview.sessionId, preview.candidates.single().id, true)
            model.importDiscoveredBooks(preview.sessionId); runCurrent()
            assertEquals(listOf(preview.candidates.single().documentUri), fixture.imported)
            assertEquals(0, folders.writes)
        } finally { owner.clear() }
    }

    @Test fun `remember preference commit blocks all conflicting library mutations and stale route callbacks`() = modelTest {
        val fixture = Fixture(); val owner = ViewModelStore(); val scanner = Scanner()
        val tree = "content://books.provider/tree/primary%3ABooks"
        val initial = BookFolderSnapshot(java.util.UUID.randomUUID().toString(), null)
        val folders = FolderFixture(initial)
        val release = CompletableDeferred<Unit>()
        folders.writeAction = { release.await() }
        try {
            val model = model(fixture, scanner, SavedStateHandle(), owner, folders)
            runCurrent(); model.bindBookFolderOwner("route", true)
            assertTrue(model.prepareDiscovery()); model.onDiscoveryTreeResult(tree)
            val preview = model.discoveryState.value as DiscoveryState.Preview
            model.rememberBookFolder("old-route", preview.sessionId, tree, initial)
            assertEquals(0, folders.writes)
            model.rememberBookFolder("route", preview.sessionId, tree, initial)
            assertTrue(model.bookFolderState.value.saving)
            assertBlocked(model, fixture)
            model.dismissDiscovery(preview.sessionId)
            assertEquals(preview, model.discoveryState.value)
            model.importDiscoveredBooks(preview.sessionId)
            assertTrue(fixture.imported.isEmpty())
            release.complete(Unit); runCurrent()
            assertFalse(model.bookFolderState.value.busy)
            assertEquals(tree, model.bookFolderState.value.snapshot!!.folder!!.treeUri)
            assertEquals(1, folders.writes)
        } finally { release.complete(Unit); owner.clear() }
    }

    @Test fun `opening check cancels actual discovery job on pause and refuses late results or automatic replay`() = modelTest {
        val fixture = Fixture(); val owner = ViewModelStore(); val scanner = Scanner(); val saved = SavedStateHandle()
        val tree = "content://books.provider/tree/primary%3ABooks"
        val initial = BookFolderSnapshot(java.util.UUID.randomUUID().toString(), SavedBookFolder(tree, "Kitaplar"), automaticScan = true)
        val release = CompletableDeferred<Unit>()
        scanner.result = { withContext(NonCancellable) { release.await() }; DiscoveryResult.Complete(listOf(candidate(0)), 1) }
        try {
            val model = model(fixture, scanner, saved, owner, FolderFixture(initial))
            runCurrent(); model.bindBookFolderOwner("route", true)
            model.reportBookFolderOpeningUi("route", loaded = true, eligible = true); runCurrent()
            assertEquals(listOf(tree), scanner.requests)
            assertTrue(model.discoveryState.value is DiscoveryState.Scanning)
            model.bindBookFolderOwner("route", false)
            assertTrue((model.discoveryState.value as DiscoveryState.Scanning).cancelRequested)
            release.complete(Unit); runCurrent()
            assertEquals(DiscoveryState.Idle, model.discoveryState.value)
            model.bindBookFolderOwner("route", true)
            model.reportBookFolderOpeningUi("route", loaded = true, eligible = true); runCurrent()
            assertEquals(listOf(tree), scanner.requests)
            assertEquals(DiscoveryState.Idle, model.discoveryState.value)
            assertTrue(fixture.imported.isEmpty()); assertEquals(0, fixture.writes)
            assertTrue(saved.keys().none { key -> saved.get<Any>(key)?.toString()?.contains("content://") == true })
        } finally { release.complete(Unit); owner.clear() }
    }

    private class FolderFixture(initial: BookFolderSnapshot) : BookFolderRepository {
        val source = MutableStateFlow(initial)
        var writes = 0
        var writeAction: (suspend () -> Unit)? = null
        override fun observe(): Flow<BookFolderSnapshot> = source
        override suspend fun save(expected: BookFolderSnapshot, selectedTreeUri: String): BookFolderWrite {
            writes++; writeAction?.invoke()
            source.value = BookFolderSnapshot(java.util.UUID.randomUUID().toString(), SavedBookFolder(selectedTreeUri, "Kitaplar"))
            return BookFolderWrite(source.value)
        }
        override suspend fun forget(expected: BookFolderSnapshot): BookFolderWrite = error("No forget during preview")
        override suspend fun retryCleanup() = Unit
        override suspend fun resolveForScan(expected: BookFolderSnapshot) = source.value.folder!!.treeUri
    }

    private fun assertBlocked(model: LibraryViewModel, fixture: Fixture) {
        assertFalse(model.prepareDiscovery())
        assertFalse(model.prepareImport())
        assertNull(model.prepareExport(book))
        assertNull(model.prepareStudyExport(book))
        assertFalse(model.prepareBackup(BackupOperation.CREATE))
        assertFalse(model.prepareBackup(BackupOperation.RESTORE))
        assertFalse(model.prepareCoverChange(book))
        model.importBook("content://must-not-import")
        model.removeBook(book)
        model.saveBookMetadata(book, "Must not write", null)
        model.saveBookOrganization(book, book.organization.copy(favorite = true))
        model.resetBookCover(book)
        assertTrue(fixture.imported.isEmpty())
        assertEquals(0, fixture.writes)
        assertEquals(BookRemovalState.Idle, model.removal.value)
        assertEquals(BookMetadataEditState.Idle, model.metadataEdit.value)
        assertEquals(BookOrganizationEditState.Idle, model.organizationEdit.value)
    }

    private fun TestScope.model(fixture: Fixture, scanner: BookDiscovery, saved: SavedStateHandle, owner: ViewModelStore,
        folders: BookFolderRepository? = null) =
        LibraryViewModel(fixture, fixture, saved, UnconfinedTestDispatcher(testScheduler),
            exporter = object : BookExporter {
                override suspend fun export(bookId: String, destinationUri: String): ExportResult { fixture.writes++; return ExportResult.Exported }
            }, remover = BookRemover { _, _ -> fixture.writes++; BookRemovalResult.Removed() },
            backup = object : LibraryBackup {
                override suspend fun create(destinationUri: String): BackupResult { fixture.writes++; return BackupResult.Created(1) }
                override suspend fun restore(sourceUri: String): BackupResult { fixture.writes++; return BackupResult.Restored(1) }
                override suspend fun recoverPendingRestore() = Unit
            }, studyExporter = object : BookStudyExporter {
                override suspend fun export(bookId: String, contentSha256: String, destinationUri: String): StudyExportResult {
                    fixture.writes++; return StudyExportResult.Failed(StudyExportFailure.NO_RECORDS)
                }
            }, coverEditor = object : BookCoverEditor {
                override suspend fun replace(bookId: String, contentSha256: String, expectedCoverId: String?, sourceUri: String): CoverEditResult {
                    fixture.writes++; return CoverEditResult.Changed()
                }
                override suspend fun reset(bookId: String, contentSha256: String, expectedCoverId: String?): CoverEditResult {
                    fixture.writes++; return CoverEditResult.Reset()
                }
            }, discovery = scanner, bookFolders = folders).also { owner.put("model", it) }

    private fun modelTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }

    private fun candidate(index: Int, name: String = "Kitap $index.pdf") =
        BookCandidate("candidate-$index", name, "content://candidate/$index", null, BookFormat.PDF)

    private inner class Scanner : BookDiscovery {
        val requests = mutableListOf<String>()
        var result: suspend () -> DiscoveryResult = { DiscoveryResult.Complete(listOf(candidate(0)), 1) }
        override suspend fun scan(treeUri: String, onProgress: (DiscoveryProgress) -> Unit): DiscoveryResult {
            requests += treeUri
            onProgress(DiscoveryProgress(1, 1))
            return result()
        }
    }

    private inner class Fixture : LibraryRepository, BookImporter {
        val books = MutableStateFlow(listOf(book))
        val imported = mutableListOf<String>()
        var writes = 0
        var importResult: suspend (String) -> ImportResult = { ImportResult.Added }
        override suspend fun import(documentUri: String): ImportResult { imported += documentUri; return importResult(documentUri) }
        override fun observeBooks(): Flow<List<Book>> = books
        override suspend fun findById(id: String) = books.value.singleOrNull { it.id == id }
        override suspend fun findByHash(sha256: String) = books.value.singleOrNull { it.sha256 == sha256 }
        override suspend fun storedFileNames() = books.value.map(Book::storageName).toSet()
        override suspend fun add(book: Book) { writes++ }
        override suspend fun updateMetadata(id: String, sha256: String, title: String, authors: String?): Boolean { writes++; return true }
        override suspend fun updateOrganization(id: String, sha256: String, organization: BookOrganization): Boolean { writes++; return true }
    }
}
