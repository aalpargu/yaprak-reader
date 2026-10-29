package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.notes.*
import com.yaprak.reader.feature.librarynotes.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryNotesExportViewModelTest {
    private val note = LibraryNote("note", "book", "a".repeat(64), "İstanbul", "Deniz", BookFormat.EPUB, "own", 1)
    private inner class Repository : LibraryNotesRepository {
        val result = MutableStateFlow(LibraryNotesResult(listOf(note), 1)); val queries = mutableListOf<String>()
        var failure = false; var finds = 0
        override fun observe(query: String) = flow { queries += query; if (failure) error("Source failed"); emitAll(result) }
        override suspend fun find(bookId: String, contentSha256: String, noteId: String): LibraryNote? { finds++; return note }
    }
    private class Exporter : LibraryNotesExporter {
        val calls = mutableListOf<Triple<String, String, LibraryNotesExportFormat>>()
        var action: (suspend () -> StudyExportResult)? = null
        override suspend fun export(query: String, destinationUri: String, format: LibraryNotesExportFormat): StudyExportResult {
            calls += Triple(query, destinationUri, format)
            return action?.invoke() ?: StudyExportResult.Exported(1, 0, 0)
        }
    }
    private suspend fun TestScope.model(block: suspend TestScope.(LibraryNotesViewModel, Repository, Exporter) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repository(); val exporter = Exporter(); val store = ViewModelStore()
        val model = LibraryNotesViewModel(repo, SavedStateHandle(), exporter); store.put("notes", model)
        try { block(model, repo, exporter) } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun obsidianReservationRestoresItsExactZipFormatQueryAndOneNamedPickerClaim() = runTest {
        val saved = SavedStateHandle(); val exporter = Exporter()
        val original = LibraryNotesExportController(exporter, saved, this)
        val request = requireNotNull(original.prepare("İstanbul 🌿", LibraryNotesExportFormat.OBSIDIAN_ZIP))
        assertEquals("Yaprak-obsidian-notlari.zip", request.suggestedFileName)
        val restored = LibraryNotesExportController(exporter, saved, this)
        assertEquals(request, restored.request.value)
        assertTrue(restored.claimLaunch(request.requestId, LibraryNotesExportFormat.OBSIDIAN_ZIP))
        assertFalse(original.claimLaunch(request.requestId, LibraryNotesExportFormat.OBSIDIAN_ZIP))
        restored.onResult(request.requestId, LibraryNotesExportFormat.MARKDOWN, "content://wrong")
        assertEquals(request, restored.request.value)
        restored.onResult(request.requestId, LibraryNotesExportFormat.OBSIDIAN_ZIP, "content://zip")
        runCurrent()
        assertEquals(listOf(Triple("İstanbul 🌿", "content://zip", LibraryNotesExportFormat.OBSIDIAN_ZIP)), exporter.calls)
        assertNull(restored.request.value)
    }

    @Test fun unavailableLoadingPausedFailedAndReadyEmptySourcesCannotCreateAnExportChoice() = runTest { model { model, repo, exporter ->
        model.showExportChoices(model.state.value); assertNull(model.exportChoice.value)
        model.setActive(true); model.showExportChoices(model.state.value); assertNull(model.exportChoice.value)
        runCurrent(); repo.result.value = LibraryNotesResult(emptyList(), 0); runCurrent()
        model.showExportChoices(model.state.value); assertNull(model.exportChoice.value)
        repo.failure = true; model.reload(); runCurrent()
        assertTrue(model.state.value.loadFailed); model.showExportChoices(model.state.value); assertNull(model.exportChoice.value)
        repo.failure = false; repo.result.value = LibraryNotesResult(listOf(note), 1); model.reload(); runCurrent()
        val ready = model.state.value; model.setActive(false); model.showExportChoices(ready)
        assertNull(model.exportChoice.value); assertNull(model.exports.request.value); assertTrue(exporter.calls.isEmpty())
        val unavailable = LibraryNotesViewModel(repo, SavedStateHandle())
        val store = ViewModelStore(); store.put("unavailable", unavailable)
        try { unavailable.setActive(true); runCurrent(); unavailable.showExportChoices(unavailable.state.value)
            assertNull(unavailable.exportChoice.value) } finally { store.clear() }
    } }

    @Test fun changedMetadataOrTotalInvalidatesChoiceAndAClosedChoiceCannotSelectTheReopenedSameQuery() = runTest { model { model, repo, exporter ->
        model.setActive(true); runCurrent(); model.showExportChoices(model.state.value)
        val old = requireNotNull(model.exportChoice.value)
        repo.result.value = LibraryNotesResult(listOf(note.copy(bookTitle = "Güncel")), 2); runCurrent()
        assertFalse(model.exportChoiceCurrent(old)); model.selectExportFormat(old.id, LibraryNotesExportFormat.MARKDOWN)
        assertNull(model.exports.request.value)
        model.dismissExportChoices(old.id); model.showExportChoices(model.state.value)
        val fresh = requireNotNull(model.exportChoice.value); assertNotEquals(old.id, fresh.id)
        model.selectExportFormat(old.id, LibraryNotesExportFormat.TEXT); model.dismissExportChoices(old.id)
        assertEquals(fresh, model.exportChoice.value); assertNull(model.exports.request.value)
        model.selectExportFormat(fresh.id, LibraryNotesExportFormat.MARKDOWN)
        assertNull(model.exportChoice.value); val request = requireNotNull(model.exports.request.value)
        assertTrue(model.exports.claimLaunch(request.requestId, request.format))
        model.exports.onResult(request.requestId, request.format, "content://fresh")
        assertEquals(listOf(Triple("", "content://fresh", LibraryNotesExportFormat.MARKDOWN)), exporter.calls)
    } }

    @Test fun pauseAndOwnerReplacementDropUnconfirmedChoiceWhileOverLimitRemainsExplicitlyUnexportable() = runTest { model { model, repo, _ ->
        val first = Any(); val second = Any()
        model.attachLifecycleOwner(first); model.setLifecycleOwnerActive(first, true); runCurrent()
        model.showExportChoices(model.state.value); val old = requireNotNull(model.exportChoice.value)
        model.attachLifecycleOwner(second); assertNull(model.exportChoice.value)
        model.setLifecycleOwnerActive(second, true); runCurrent()
        model.setLifecycleOwnerActive(first, false); assertTrue(model.isLifecycleOwnerActive(second))
        model.selectExportFormat(old.id, LibraryNotesExportFormat.TEXT); assertNull(model.exports.request.value)
        repo.result.value = LibraryNotesResult(listOf(note), 1001); runCurrent()
        model.showExportChoices(model.state.value); val limited = requireNotNull(model.exportChoice.value)
        model.selectExportFormat(limited.id, LibraryNotesExportFormat.MARKDOWN)
        assertEquals(limited, model.exportChoice.value); assertNull(model.exports.request.value)
        model.setLifecycleOwnerActive(second, false); assertNull(model.exportChoice.value)
    } }

    @Test fun pendingAndRunningExportBlockOldSearchCopyOpenReloadAndKeepTheReservedQueryUntilResult() = runTest { model { model, repo, exporter ->
        model.setActive(true); runCurrent(); model.search("istanbul Deniz"); advanceTimeBy(251); runCurrent()
        model.showExportChoices(model.state.value); val choice = requireNotNull(model.exportChoice.value)
        model.search("behind modal"); assertEquals(choice.query, model.state.value.query)
        model.selectExportFormat(choice.id, LibraryNotesExportFormat.TEXT)
        val request = requireNotNull(model.exports.request.value); val reads = repo.queries.size
        var effects = 0
        model.search("wrong query"); model.reload(); model.copy(note) { effects++ }; model.open(note) { effects++ }
        assertEquals(0, effects); assertEquals(reads, repo.queries.size); assertEquals(choice.query, model.state.value.query)
        val finish = CompletableDeferred<Unit>()
        exporter.action = { finish.await(); StudyExportResult.Exported(230, 0, 0) }
        assertTrue(model.exports.claimLaunch(request.requestId, request.format))
        model.exports.onResult(request.requestId, request.format, "content://all-notes")
        assertTrue(model.exports.exporting.value); model.search("still wrong"); model.reload()
        repo.result.value = LibraryNotesResult(listOf(note.copy(bookTitle = "New")), 230); runCurrent()
        assertEquals(choice.query, exporter.calls.single().first)
        finish.complete(Unit); runCurrent(); assertFalse(model.exports.busy)
        val notice = requireNotNull(model.exports.notice.value)
        assertEquals(choice.query, notice.query); assertEquals(StudyExportResult.Exported(230, 0, 0), notice.result)
        model.search("before acknowledge"); assertEquals(choice.query, model.state.value.query)
        model.exports.acknowledge(notice.id); model.search("new query")
        assertEquals("new query", model.state.value.query)
    } }

    @Test fun namedRestoredReservationClaimsOnceAndOldWrongPrematureOrDuplicateResultsCannotConsumeItOrANewSameQuery() = runTest {
        val saved = SavedStateHandle(); val exporter = Exporter()
        val first = LibraryNotesExportController(exporter, saved, this)
        val request = requireNotNull(first.prepare("İstanbul", LibraryNotesExportFormat.MARKDOWN))
        assertEquals("text/markdown", request.format.mimeType); assertEquals("Yaprak-kutuphane-notlari.md", request.suggestedFileName)
        val current = LibraryNotesExportController(exporter, saved, this); assertEquals(request, current.request.value)
        current.onResult(request.requestId, request.format, "content://premature"); assertTrue(exporter.calls.isEmpty())
        assertTrue(current.claimLaunch(request.requestId, request.format)); assertFalse(first.claimLaunch(request.requestId, request.format))
        current.onResult(request.requestId, LibraryNotesExportFormat.TEXT, "content://wrong")
        current.onLaunchFailed("old", request.format); assertEquals(request, current.request.value)
        current.onResult(request.requestId, request.format, "content://correct")
        first.onResult(request.requestId, request.format, "content://old-owner")
        assertEquals(1, exporter.calls.size)
        val second = requireNotNull(current.prepare(request.query, request.format)); assertNotEquals(request.requestId, second.requestId)
        first.onResult(request.requestId, request.format, "content://late")
        current.onResult(request.requestId, request.format, "content://duplicate")
        assertEquals(second, current.request.value); assertTrue(current.claimLaunch(second.requestId, second.format))
        current.onResult(second.requestId, second.format, null); assertNull(current.request.value); assertEquals(1, exporter.calls.size)
    }

    @Test fun malformedReservationOrQueryCannotLaunchAndCancellationCannotPublishALateSuccess() = runTest {
        val exporter = Exporter()
        val saved = SavedStateHandle(mapOf("libraryNotesExportReservationV1" to arrayListOf("bad", "query", "TEXT", "true")))
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val controller = LibraryNotesExportController(exporter, saved, scope)
        try {
            assertNull(controller.request.value); assertFalse(saved.contains("libraryNotesExportReservationV1"))
            assertNull(controller.prepare("bad\u0000", LibraryNotesExportFormat.TEXT))
            assertNull(controller.prepare("x".repeat(201), LibraryNotesExportFormat.TEXT))
            val failed = requireNotNull(controller.prepare("", LibraryNotesExportFormat.TEXT))
            assertTrue(controller.claimLaunch(failed.requestId, failed.format)); controller.onLaunchFailed(failed.requestId, failed.format)
            val notice = requireNotNull(controller.notice.value); assertEquals(StudyExportResult.Failed(StudyExportFailure.ACCESS), notice.result)
            controller.acknowledge(notice.id - 1); assertNotNull(controller.notice.value); controller.acknowledge(notice.id)
            val finish = CompletableDeferred<Unit>(); exporter.action = { withContext(NonCancellable) { finish.await() }; StudyExportResult.Exported(1, 0, 0) }
            val request = requireNotNull(controller.prepare("", LibraryNotesExportFormat.MARKDOWN))
            assertTrue(controller.claimLaunch(request.requestId, request.format)); controller.onResult(request.requestId, request.format, "content://late")
            scope.cancel(); finish.complete(Unit); runCurrent()
            assertNull(controller.notice.value); assertFalse(controller.exporting.value)
        } finally { scope.cancel() }
    }
}
