package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.notes.LibraryNote
import com.yaprak.reader.domain.notes.LibraryNotesRepository
import com.yaprak.reader.domain.notes.LibraryNotesResult
import com.yaprak.reader.feature.librarynotes.LibraryNoteFeedbackKind
import com.yaprak.reader.feature.librarynotes.LibraryNotesViewModel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class LibraryNotesViewModelTest {
    private val note = LibraryNote("note", "book", "a".repeat(64), "İstanbul", "Deniz", BookFormat.PDF,
        "İ ı 😀\nİkinci satır e\u0301", 3, 7)

    private inner class Repository : LibraryNotesRepository {
        val result = MutableStateFlow(LibraryNotesResult(listOf(note), 1))
        val queries = mutableListOf<String>()
        val finds = mutableListOf<Triple<String, String, String>>()
        var activeCollectors = 0
        var failRead = false
        var findAction: (suspend () -> LibraryNote?)? = null
        override fun observe(query: String) = flow {
            queries += query
            activeCollectors++
            try {
                if (failRead) throw IOException()
                emitAll(result)
            } finally { activeCollectors-- }
        }
        override suspend fun find(bookId: String, contentSha256: String, noteId: String): LibraryNote? {
            finds += Triple(bookId, contentSha256, noteId)
            findAction?.let { return it.invoke() }
            return result.value.entries.singleOrNull {
                it.bookId == bookId && it.contentSha256 == contentSha256 && it.id == noteId
            }
        }
    }

    private suspend fun TestScope.withModel(
        saved: SavedStateHandle = SavedStateHandle(),
        activate: Boolean = true,
        block: suspend TestScope.(LibraryNotesViewModel, Repository) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        val repository = Repository()
        try {
            val model = LibraryNotesViewModel(repository, saved).also { owner.put("notes", it) }
            if (activate) model.setActive(true)
            runCurrent()
            block(model, repository)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun `restored and edited queries stay bounded without splitting surrogate pairs`() = runTest {
        val original = "x".repeat(199) + "😀" + "z".repeat(20)
        val saved = SavedStateHandle(mapOf(LibraryNotesViewModel.QUERY to original))
        withModel(saved, activate = false) { model, repository ->
            assertEquals("x".repeat(199), model.state.value.query)
            assertEquals("x".repeat(199), saved.get<String>(LibraryNotesViewModel.QUERY))
            assertTrue(repository.queries.isEmpty())
            model.search("y".repeat(198) + "😀more")
            assertEquals("y".repeat(198) + "😀", model.state.value.query)
            model.setActive(true)
            runCurrent()
            assertEquals(listOf("y".repeat(198) + "😀"), repository.queries)
        }
        withModel(SavedStateHandle(mapOf(LibraryNotesViewModel.QUERY to 42)), activate = false) { model, _ ->
            assertEquals("", model.state.value.query)
        }
    }

    @Test fun `new query immediately removes old actions and opens only final debounced query`() = runTest {
        withModel { model, repository ->
            assertTrue(model.state.value.actionsEnabled)
            model.search("a")
            assertTrue(model.state.value.loading)
            assertTrue(model.state.value.entries.isEmpty())
            var copied = false
            model.copy(note) { copied = true }
            advanceTimeBy(200)
            model.search("istanbul")
            advanceTimeBy(249)
            runCurrent()
            assertEquals(listOf(""), repository.queries)
            assertFalse(copied)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("", "istanbul"), repository.queries)
            assertTrue(model.state.value.actionsEnabled)
        }
    }

    @Test fun `late emission from cancellation ignoring old flow cannot replace results during debounce`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        val newest = note.copy(id = "newest")
        val repository = object : LibraryNotesRepository {
            override fun observe(query: String): Flow<LibraryNotesResult> = if (query.isNotEmpty())
                flow { emit(LibraryNotesResult(listOf(newest), 1)) }
            else object : Flow<LibraryNotesResult> {
                override suspend fun collect(collector: FlowCollector<LibraryNotesResult>) {
                    collector.emit(LibraryNotesResult(listOf(note), 1))
                    try { awaitCancellation() }
                    catch (_: CancellationException) {
                        withContext(NonCancellable) { delay(10); collector.emit(LibraryNotesResult(listOf(note), 999)) }
                    }
                }
            }
            override suspend fun find(bookId: String, contentSha256: String, noteId: String) = note
        }
        try {
            val model = LibraryNotesViewModel(repository, SavedStateHandle()).also { owner.put("notes", it) }
            model.setActive(true)
            runCurrent()
            model.search("new")
            advanceTimeBy(10)
            runCurrent()
            assertTrue(model.state.value.loading)
            assertTrue(model.state.value.entries.isEmpty())
            assertEquals(0, model.state.value.totalCount)
            advanceTimeBy(240)
            runCurrent()
            assertEquals(listOf(newest), model.state.value.entries)
            assertEquals(1, model.state.value.totalCount)
        } finally { owner.clear(); advanceTimeBy(10); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun `source failure remains distinct from empty search and retry preserves query`() = runTest {
        withModel { model, repository ->
            repository.failRead = true
            model.search("keep")
            advanceTimeBy(250)
            runCurrent()
            assertTrue(model.state.value.loadFailed)
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.actionsEnabled)
            assertEquals("keep", model.state.value.query)
            repository.failRead = false
            repository.result.value = LibraryNotesResult(emptyList(), 0)
            model.reload()
            runCurrent()
            assertFalse(model.state.value.loadFailed)
            assertEquals("keep", repository.queries.last())
            assertEquals(0, model.state.value.totalCount)
        }
    }

    @Test fun `explicit copy and open revalidate identity and preserve exact saved payload`() = runTest {
        withModel { model, repository ->
            val copied = mutableListOf<String>()
            val opened = mutableListOf<String>()
            assertTrue(repository.finds.isEmpty())
            model.copy(note, copied::add)
            runCurrent()
            assertEquals(listOf(note.text), copied)
            assertEquals(LibraryNoteFeedbackKind.COPIED, model.state.value.feedback?.kind)
            model.open(note, opened::add)
            runCurrent()
            assertEquals(listOf(note.bookId), opened)
            assertEquals(List(2) { Triple(note.bookId, note.contentSha256, note.id) }, repository.finds)
            assertNull(model.state.value.feedback)
        }
    }

    @Test fun `find rejects changed text hash book metadata or removed row despite unchanged visible id`() = runTest {
        withModel { model, repository ->
            var effects = 0
            val revisions = listOf(note.copy(text = "Changed"), note.copy(contentSha256 = "b".repeat(64)),
                note.copy(bookId = "other"), note.copy(bookTitle = "Renamed"), note.copy(pdfPageIndex = 8), null)
            revisions.forEach { revision ->
                repository.findAction = { revision }
                model.copy(note) { effects++ }
                runCurrent()
                assertEquals(LibraryNoteFeedbackKind.CHANGED, model.state.value.feedback?.kind)
            }
            repository.result.value = LibraryNotesResult(emptyList(), 0)
            runCurrent()
            model.open(note) { effects++ }
            runCurrent()
            assertEquals(0, effects)
            assertNull(model.state.value.feedback)
        }
    }

    @Test fun `same id edited while find suspends cancels action and stale callbacks cannot use new text`() = runTest {
        withModel { model, repository ->
            val pending = CompletableDeferred<LibraryNote>()
            repository.findAction = { pending.await() }
            val copied = mutableListOf<String>()
            model.copy(note, copied::add)
            runCurrent()
            assertEquals(note, model.state.value.action)
            val changed = note.copy(text = "Updated saved note")
            repository.result.value = LibraryNotesResult(listOf(changed), 1)
            runCurrent()
            pending.complete(note)
            runCurrent()
            model.copy(note, copied::add)
            runCurrent()
            assertTrue(copied.isEmpty())
            assertNull(model.state.value.action)
            repository.findAction = { changed }
            model.copy(changed, copied::add)
            runCurrent()
            assertEquals(listOf(changed.text), copied)
        }
    }

    @Test fun `double action is rejected and query reload or pause cancels even cancellation ignoring find`() = runTest {
        withModel { model, repository ->
            var effects = 0
            suspend fun pendingAction(cancel: () -> Unit) {
                val pending = CompletableDeferred<LibraryNote>()
                repository.findAction = { withContext(NonCancellable) { pending.await() } }
                val before = repository.finds.size
                model.copy(note) { effects++ }
                model.open(note) { effects++ }
                runCurrent()
                assertEquals(before + 1, repository.finds.size)
                cancel()
                pending.complete(note)
                runCurrent()
                assertEquals(0, effects)
                assertNull(model.state.value.action)
                assertNull(model.state.value.feedback)
            }
            pendingAction { model.search("new") }
            advanceTimeBy(250)
            runCurrent()
            pendingAction(model::reload)
            pendingAction { model.setActive(false) }
            assertEquals(0, repository.activeCollectors)
        }
    }

    @Test fun `paused screen unsubscribes without background scans and resume obtains new source state`() = runTest {
        withModel { model, repository ->
            assertEquals(1, repository.activeCollectors)
            model.setActive(false)
            runCurrent()
            assertEquals(0, repository.activeCollectors)
            val calls = repository.queries.size
            repository.result.value = LibraryNotesResult(listOf(note.copy(text = "While paused")), 42)
            model.search("paused query")
            model.reload()
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(calls, repository.queries.size)
            assertTrue(model.state.value.entries.isEmpty())
            model.setActive(true)
            runCurrent()
            assertEquals("paused query", repository.queries.last())
            assertEquals("While paused", model.state.value.entries.single().text)
            assertEquals(42, model.state.value.totalCount)
        }
    }

    @Test fun `stale Activity pause or dispose cannot deactivate replacement owner or cancel its pending action`() = runTest {
        withModel(activate = false) { model, repository ->
            val oldOwner = Any()
            val replacementOwner = Any()
            model.attachLifecycleOwner(oldOwner)
            model.setLifecycleOwnerActive(oldOwner, true)
            runCurrent()
            model.attachLifecycleOwner(replacementOwner)
            model.setLifecycleOwnerActive(replacementOwner, true)
            runCurrent()
            assertFalse(model.isLifecycleOwnerActive(oldOwner))
            assertTrue(model.isLifecycleOwnerActive(replacementOwner))
            val pending = CompletableDeferred<LibraryNote>()
            repository.findAction = { pending.await() }
            var effects = 0
            model.copy(note) { effects++ }
            runCurrent()
            model.setLifecycleOwnerActive(oldOwner, false)
            model.detachLifecycleOwner(oldOwner)
            runCurrent()
            assertTrue(model.state.value.active)
            assertEquals(note, model.state.value.action)
            assertEquals(1, repository.activeCollectors)
            pending.complete(note)
            runCurrent()
            assertEquals(1, effects)
            assertEquals(LibraryNoteFeedbackKind.COPIED, model.state.value.feedback?.kind)
            model.detachLifecycleOwner(replacementOwner)
            runCurrent()
            assertFalse(model.state.value.active)
            assertEquals(0, repository.activeCollectors)
        }
    }

    @Test fun `clipboard or reader failure is retryable and no implicit action runs on invalidation`() = runTest {
        withModel { model, repository ->
            model.copy(note) { throw IOException() }
            runCurrent()
            assertEquals(LibraryNoteFeedbackKind.COPY_FAILED, model.state.value.feedback?.kind)
            model.open(note) { throw IOException() }
            runCurrent()
            assertEquals(LibraryNoteFeedbackKind.OPEN_FAILED, model.state.value.feedback?.kind)
            var copied = ""
            model.copy(note) { copied = it }
            runCurrent()
            assertEquals(note.text, copied)
            repository.result.value = LibraryNotesResult(listOf(note.copy(bookTitle = "New title")), 1)
            runCurrent()
            assertNull(model.state.value.feedback)
            assertEquals(3, repository.finds.size)
        }
    }
}
