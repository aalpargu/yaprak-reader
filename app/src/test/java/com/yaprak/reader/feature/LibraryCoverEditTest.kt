package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.backup.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCoverEditTest {
    private val cover = BookCover("123e4567-e89b-12d3-a456-426614174000",
        "123e4567-e89b-12d3-a456-426614174000.png", "image/png", 20, "b".repeat(64), 20, 30)
    private val book = Book("one", "Başlık", "Yazar", BookFormat.EPUB,
        "one.epub", "one.epub", 30, "a".repeat(64), 1, customCover = cover)
    private val other = book.copy(id = "two", sha256 = "c".repeat(64), customCover = null)

    @Test fun `picker recreation keeps exact revision and consumes URI once`() = modelTest {
        val repository = Repository(listOf(book, other))
        val editor = Editor()
        val saved = SavedStateHandle()
        val firstOwner = ViewModelStore()
        val first = model(repository, editor, saved, firstOwner)
        runCurrent()
        assertTrue(first.prepareCoverChange(book))
        assertFalse(first.prepareCoverChange(other))
        assertFalse(saved.keys().any { saved.get<Any>(it)?.toString()?.contains("content://") == true })
        firstOwner.clear()
        val nextOwner = ViewModelStore()
        try {
            val restored = model(repository, editor, saved, nextOwner)
            assertTrue(restored.coverPickerPending.value)
            restored.onCoverDocumentResult("content://selected-cover")
            restored.onCoverDocumentResult("content://unexpected-second-result")
            // Even an old restored controller cannot consume the shared saved reservation again.
            first.onCoverDocumentResult("content://late-old-activity")
            runCurrent()
            assertEquals(listOf(Editor.Request(book.id, book.sha256, cover.id, "content://selected-cover")), editor.requests)
            assertTrue((restored.coverEdit.value as CoverEditState.Finished).result is CoverEditResult.Changed)
            assertFalse(restored.coverPickerPending.value)
            assertFalse(restored.coverEditing.value)
            assertFalse(saved.keys().any { it.startsWith("pendingCover") })
        } finally { nextOwner.clear() }
    }

    @Test fun `cancellation and failed launcher never write or claim success`() = modelTest {
        val repository = Repository(listOf(book))
        val editor = Editor()
        val owner = ViewModelStore()
        try {
            val model = model(repository, editor, SavedStateHandle(), owner)
            runCurrent()
            assertTrue(model.prepareCoverChange(book))
            model.onCoverDocumentResult(null)
            assertEquals(CoverEditState.Idle, model.coverEdit.value)
            assertTrue(editor.requests.isEmpty())
            assertTrue(model.prepareCoverChange(book))
            model.onCoverLaunchFailed()
            assertEquals(CoverEditResult.Failed(CoverEditFailure.ACCESS),
                (model.coverEdit.value as CoverEditState.Finished).result)
            assertEquals(book, repository.books.value.single())
            assertTrue(editor.requests.isEmpty())
            assertTrue(model.prepareCoverChange(book))
            model.onCoverDocumentResult(null)
        } finally { owner.clear() }
    }

    @Test fun `late old picker callback cannot clear a newer restored reservation`() = modelTest {
        val repository = Repository(listOf(book, other))
        val editor = Editor()
        val saved = SavedStateHandle()
        val firstOwner = ViewModelStore()
        val nextOwner = ViewModelStore()
        try {
            val first = model(repository, editor, saved, firstOwner)
            runCurrent()
            assertTrue(first.prepareCoverChange(book))
            firstOwner.clear()
            val restored = model(repository, editor, saved, nextOwner)
            runCurrent()
            restored.onCoverDocumentResult(null)
            // A new request for the very same book/revision still needs its own reservation token.
            assertTrue(restored.prepareCoverChange(book))
            first.onCoverDocumentResult("content://late-old-result")
            assertTrue(restored.coverPickerPending.value)
            assertEquals(book.id, saved.get<String>("pendingCoverBookId"))
            restored.onCoverDocumentResult("content://new-result")
            runCurrent()
            assertEquals(listOf(Editor.Request(book.id, book.sha256, cover.id, "content://new-result")), editor.requests)
        } finally { firstOwner.clear(); nextOwner.clear() }
    }

    @Test fun `same hash new cover revision rejects pending result and old reset`() = modelTest {
        val repository = Repository(listOf(book))
        val editor = Editor()
        val owner = ViewModelStore()
        try {
            val model = model(repository, editor, SavedStateHandle(), owner)
            runCurrent()
            assertTrue(model.prepareCoverChange(book))
            val newer = book.copy(customCover = cover.copy(id = "new-revision", storageName = "new-revision.png"))
            repository.books.value = listOf(newer)
            runCurrent()
            model.onCoverDocumentResult("content://old-selection")
            runCurrent()
            assertEquals(CoverEditResult.Stale, (model.coverEdit.value as CoverEditState.Finished).result)
            assertTrue(editor.requests.isEmpty())
            model.resetBookCover(book)
            runCurrent()
            assertTrue(editor.resets.isEmpty())
            assertFalse(model.prepareCoverChange(book))
            assertEquals(newer, repository.books.value.single())
            model.resetBookCover(newer)
            runCurrent()
            assertEquals(listOf(Editor.Request(newer.id, newer.sha256, newer.customCover!!.id, null)), editor.resets)
        } finally { owner.clear() }
    }

    @Test fun `absence token is exact and missing book consumes reservation safely`() = modelTest {
        val repository = Repository(listOf(other))
        val editor = Editor()
        val owner = ViewModelStore()
        try {
            val model = model(repository, editor, SavedStateHandle(), owner)
            runCurrent()
            assertTrue(model.prepareCoverChange(other))
            model.onCoverDocumentResult("content://first-artwork")
            runCurrent()
            assertNull(editor.requests.single().revision)
            assertTrue(model.prepareCoverChange(other))
            repository.books.value = emptyList()
            runCurrent()
            model.onCoverDocumentResult("content://deleted-book")
            runCurrent()
            assertEquals(CoverEditResult.Missing, (model.coverEdit.value as CoverEditState.Finished).result)
            assertEquals(1, editor.requests.size)
            assertFalse(model.coverPickerPending.value)
        } finally { owner.clear() }
    }

    @Test fun `pending and running cover block every conflicting library operation`() = modelTest {
        val repository = Repository(listOf(book))
        val editor = Editor()
        val release = CompletableDeferred<Unit>()
        editor.replaceResult = { release.await(); CoverEditResult.Changed() }
        val owner = ViewModelStore()
        try {
            val model = model(repository, editor, SavedStateHandle(), owner)
            runCurrent()
            assertTrue(model.prepareCoverChange(book))
            assertConflictingActionsBlocked(model, repository, book)
            model.onCoverDocumentResult("content://cover")
            runCurrent()
            assertTrue(model.coverEditing.value)
            assertConflictingActionsBlocked(model, repository, book)
            release.complete(Unit)
            runCurrent()
            assertFalse(model.coverEditing.value)
            assertTrue(model.prepareImport())
            assertFalse(model.prepareCoverChange(book))
            model.resetBookCover(book)
            assertTrue(editor.resets.isEmpty())
            model.onImportDocumentResult(emptyList())
            assertNotNull(model.prepareExport(book))
            assertFalse(model.prepareCoverChange(book))
            model.onExportDocumentResult(book.format, null)
            assertNotNull(model.prepareStudyExport(book))
            assertFalse(model.prepareCoverChange(book))
            model.onStudyExportDocumentResult(null)
            assertTrue(model.prepareBackup(BackupOperation.CREATE))
            assertFalse(model.prepareCoverChange(book))
            model.onBackupDocumentResult(BackupOperation.CREATE, null)
            assertTrue(model.prepareCoverChange(book))
            model.onCoverDocumentResult(null)
        } finally { owner.clear() }
    }

    @Test fun `editor cancellation releases busy and cannot publish changed`() = modelTest {
        val repository = Repository(listOf(book))
        val editor = Editor()
        var cancelled = false
        editor.replaceResult = { try { awaitCancellation() } finally { cancelled = true } }
        val owner = ViewModelStore()
        val model = model(repository, editor, SavedStateHandle(), owner)
        runCurrent()
        assertTrue(model.prepareCoverChange(book))
        model.onCoverDocumentResult("content://cover")
        runCurrent()
        assertTrue(model.coverEditing.value)
        owner.clear()
        runCurrent()
        assertTrue(cancelled)
        assertFalse(model.coverEditing.value)
        assertEquals(CoverEditState.Idle, model.coverEdit.value)
        assertEquals(book, repository.books.value.single())
    }

    @Test fun `invalid restoration and absent editor leave no fake pending success`() = modelTest {
        val repository = Repository(listOf(book))
        val owner = ViewModelStore()
        try {
            val invalid = SavedStateHandle(mapOf("pendingCoverBookId" to book.id,
                "pendingCoverContentSha256" to book.sha256, "pendingCoverExpectedId" to 7))
            val model = model(repository, Editor(), invalid, owner)
            runCurrent()
            assertFalse(model.coverPickerPending.value)
            assertFalse(invalid.keys().any { it.startsWith("pendingCover") })
            val absent = LibraryViewModel(repository, failingImporter, SavedStateHandle(),
                UnconfinedTestDispatcher(testScheduler)).also { owner.put("absent", it) }
            runCurrent()
            assertFalse(absent.coverEditAvailable)
            assertFalse(absent.prepareCoverChange(book))
            absent.onCoverDocumentResult("content://ignored")
            absent.resetBookCover(book)
            assertEquals(CoverEditState.Idle, absent.coverEdit.value)
        } finally { owner.clear() }
    }

    @Test fun `failed write retains previous cover and permits a real retry`() = modelTest {
        val repository = Repository(listOf(book))
        val editor = Editor().apply { replaceResult = { throw IllegalStateException("write failed") } }
        val owner = ViewModelStore()
        try {
            val model = model(repository, editor, SavedStateHandle(), owner)
            runCurrent()
            assertTrue(model.prepareCoverChange(book))
            model.onCoverDocumentResult("content://first")
            runCurrent()
            assertEquals(CoverEditResult.Failed(CoverEditFailure.IO), (model.coverEdit.value as CoverEditState.Finished).result)
            assertEquals(book, repository.books.value.single())
            editor.replaceResult = { CoverEditResult.Changed(privateFileMayRemain = true) }
            assertTrue(model.prepareCoverChange(book))
            model.onCoverDocumentResult("content://retry")
            runCurrent()
            assertEquals(CoverEditResult.Changed(true), (model.coverEdit.value as CoverEditState.Finished).result)
            assertEquals(2, editor.requests.size)
        } finally { owner.clear() }
    }

    private fun assertConflictingActionsBlocked(model: LibraryViewModel, repository: Repository, book: Book) {
        assertFalse(model.prepareImport())
        assertNull(model.prepareExport(book))
        assertNull(model.prepareStudyExport(book))
        assertFalse(model.prepareBackup(BackupOperation.CREATE))
        assertFalse(model.prepareBackup(BackupOperation.RESTORE))
        model.importBook("content://must-not-import")
        model.removeBook(book)
        model.saveBookMetadata(book, "Must not write", "Author")
        model.saveBookOrganization(book, book.organization.copy(favorite = true))
        model.resetBookCover(book)
        assertEquals(0, repository.writes)
        assertEquals(BookRemovalState.Idle, model.removal.value)
        assertEquals(BookMetadataEditState.Idle, model.metadataEdit.value)
        assertEquals(BookOrganizationEditState.Idle, model.organizationEdit.value)
    }

    private fun TestScope.model(repository: Repository, editor: Editor, saved: SavedStateHandle, owner: ViewModelStore) =
        LibraryViewModel(repository, failingImporter, saved, UnconfinedTestDispatcher(testScheduler),
            exporter = object : BookExporter { override suspend fun export(bookId: String, destinationUri: String): ExportResult = error("Unexpected export") },
            remover = BookRemover { _, _ -> error("Unexpected remove") },
            backup = object : LibraryBackup {
                override suspend fun create(destinationUri: String): BackupResult = error("Unexpected backup")
                override suspend fun restore(sourceUri: String): BackupResult = error("Unexpected restore")
                override suspend fun recoverPendingRestore() = Unit
            }, studyExporter = object : BookStudyExporter {
                override suspend fun export(bookId: String, contentSha256: String, destinationUri: String): StudyExportResult = error("Unexpected study export")
            }, coverEditor = editor).also { owner.put("model", it) }

    private fun modelTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }

    private val failingImporter = object : BookImporter {
        override suspend fun import(documentUri: String): ImportResult = error("Unexpected import")
    }
    private class Editor : BookCoverEditor {
        data class Request(val id: String, val hash: String, val revision: String?, val uri: String?)
        val requests = mutableListOf<Request>()
        val resets = mutableListOf<Request>()
        var replaceResult: suspend () -> CoverEditResult = { CoverEditResult.Changed() }
        override suspend fun replace(bookId: String, contentSha256: String, expectedCoverId: String?, sourceUri: String): CoverEditResult {
            requests += Request(bookId, contentSha256, expectedCoverId, sourceUri)
            return replaceResult()
        }
        override suspend fun reset(bookId: String, contentSha256: String, expectedCoverId: String?): CoverEditResult {
            resets += Request(bookId, contentSha256, expectedCoverId, null)
            return CoverEditResult.Reset()
        }
    }
    private class Repository(initial: List<Book>) : LibraryRepository {
        val books = MutableStateFlow(initial)
        var writes = 0
        override fun observeBooks(): Flow<List<Book>> = books
        override suspend fun findById(id: String) = books.value.singleOrNull { it.id == id }
        override suspend fun findByHash(sha256: String) = books.value.singleOrNull { it.sha256 == sha256 }
        override suspend fun storedFileNames() = books.value.map(Book::storageName).toSet()
        override suspend fun add(book: Book) { writes++ }
        override suspend fun updateMetadata(id: String, sha256: String, title: String, authors: String?): Boolean { writes++; return true }
        override suspend fun updateOrganization(id: String, sha256: String, organization: BookOrganization): Boolean { writes++; return true }
    }
}
