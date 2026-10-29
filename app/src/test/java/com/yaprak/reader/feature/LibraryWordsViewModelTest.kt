package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.vocabulary.*
import com.yaprak.reader.feature.librarywords.LibraryWordsViewModel
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryWordsViewModelTest {
    private val first = LibraryWord(SavedWord("one", "tree", "ağaç", 2), "book-a", "a".repeat(64), "Birinci", null)
    private val second = LibraryWord(SavedWord("two", "leaf", "yaprak", 1), "book-b", "b".repeat(64), "İkinci", "Yazar")
    private inner class Source : LibraryWordsRepository {
        val result = MutableStateFlow(LibraryWordsResult(listOf(first, second), 2))
        val queries = mutableListOf<String>()
        var findAction: suspend () -> LibraryWord? = { result.value.entries.firstOrNull() }
        var failure = false
        override fun observe(query: String) = flow {
            queries += query
            if (failure) throw IOException()
            emitAll(result)
        }
        override suspend fun find(bookId: String, contentSha256: String, wordId: String) = findAction()
    }
    private suspend fun TestScope.fixture(saved: SavedStateHandle = SavedStateHandle(),
        test: suspend TestScope.(LibraryWordsViewModel, Source, Any) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        val source = Source()
        val owner = Any()
        try {
            val model = LibraryWordsViewModel(source, saved).also { models.put("words", it) }
            model.attach(owner)
            assertTrue(source.queries.isEmpty())
            model.active(owner, true); runCurrent()
            test(model, source, owner)
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `practice reveals only on current action and source edits deletion or scope changes hide the answer`() = runTest {
        fixture { model, source, _ ->
            model.practice(model.state.value.revision, "start")
            assertEquals(first, model.state.value.current); assertNull(model.state.value.practice!!.revealed)
            val old = model.state.value.revision
            model.practice(old, "reveal"); assertEquals(first.word, model.state.value.practice!!.revealed)
            model.practice(old, "next"); assertEquals(first, model.state.value.current)
            val edited = first.copy(word = first.word.copy(turkish = "düzenlendi"))
            source.result.value = LibraryWordsResult(listOf(edited, second), 2); runCurrent()
            assertEquals(edited, model.state.value.current); assertNull(model.state.value.practice!!.revealed)
            source.result.value = LibraryWordsResult(listOf(second), 1); runCurrent()
            assertEquals(second, model.state.value.current); assertNull(model.state.value.practice!!.revealed)
            model.search("leaf"); assertNull(model.state.value.practice); assertTrue(model.state.value.loading)
            advanceTimeBy(250); runCurrent(); assertEquals("leaf", source.queries.last())
        }
    }

    @Test fun `new cards preserve individual books and movement shuffle and stop do not write learning history`() = runTest {
        fixture { model, _, _ ->
            model.practice(model.state.value.revision, "start")
            model.practice(model.state.value.revision, "next")
            assertEquals(second, model.state.value.current)
            model.practice(model.state.value.revision, "reveal")
            model.practice(model.state.value.revision, "previous")
            assertEquals(first, model.state.value.current); assertNull(model.state.value.practice!!.revealed)
            model.practice(model.state.value.revision, "shuffle")
            assertEquals(2, model.state.value.practice!!.cards(model.state.value.entries.map { it.word }).size)
            assertNull(model.state.value.practice!!.revealed)
            model.practice(model.state.value.revision, "stop"); assertNull(model.state.value.practice)
        }
    }

    @Test fun `pause replaced owners and query restore preserve only bounded query with no stale practice`() = runTest {
        val saved = SavedStateHandle(mapOf(LibraryWordsViewModel.QUERY to "a".repeat(199) + "😀tail"))
        fixture(saved) { model, source, owner ->
            assertEquals("a".repeat(199), model.state.value.query)
            model.practice(model.state.value.revision, "start")
            model.practice(model.state.value.revision, "reveal")
            model.active(owner, false)
            assertNull(model.state.value.practice); assertTrue(model.state.value.entries.isEmpty())
            val newOwner = Any(); model.attach(newOwner); model.active(newOwner, true); runCurrent()
            model.detach(owner); assertTrue(model.isActive(newOwner))
            model.active(owner, false); assertTrue(model.state.value.active)
            assertEquals(setOf(LibraryWordsViewModel.QUERY), saved.keys())
            assertEquals(2, source.queries.size)
        }
    }

    @Test fun `open rechecks entire current entry and rejects changed or cancelled late find`() = runTest {
        fixture { model, source, owner ->
            val opened = mutableListOf<String>()
            source.findAction = { first.copy(bookTitle = "Changed") }
            model.open(model.state.value.revision, first, opened::add); runCurrent()
            assertTrue(opened.isEmpty()); assertEquals("changed", model.state.value.feedback)
            val pending = CompletableDeferred<LibraryWord>()
            source.findAction = { withContext(NonCancellable) { pending.await() } }
            model.open(model.state.value.revision, first, opened::add); runCurrent()
            assertTrue(model.state.value.busy)
            model.active(owner, false); pending.complete(first); runCurrent()
            assertTrue(opened.isEmpty()); assertFalse(model.state.value.busy)
            model.active(owner, true); runCurrent()
            source.findAction = { first }
            model.open(model.state.value.revision, first, opened::add); runCurrent()
            assertEquals(listOf(first.bookId), opened)
        }
    }

    @Test fun `failed source clears cards shows failure and current explicit reload can retry`() = runTest {
        fixture { model, source, _ ->
            model.practice(model.state.value.revision, "start")
            source.failure = true
            model.reload(model.state.value.revision); runCurrent()
            assertTrue(model.state.value.failed); assertTrue(model.state.value.entries.isEmpty()); assertNull(model.state.value.practice)
            source.failure = false
            val old = model.state.value.revision
            model.reload(old); runCurrent()
            assertFalse(model.state.value.failed)
            val revision = model.state.value.revision
            model.reload(old); assertEquals(revision, model.state.value.revision)
        }
    }
}
