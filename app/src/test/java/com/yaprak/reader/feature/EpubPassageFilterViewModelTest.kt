package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.domain.passages.EpubPassageRepository
import com.yaprak.reader.feature.passages.EpubPassageSort
import com.yaprak.reader.feature.passages.EpubPassagesViewModel
import com.yaprak.reader.reader.SourceTextAnchor
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpubPassageFilterViewModelTest {
    private fun passage(id: String, text: String) = EpubPassage(
        id, text, SourceTextAnchor("$id.xhtml", 0, text.length, "a".repeat(64)), 1,
    )

    private class Repository(initial: List<EpubPassage>) : EpubPassageRepository {
        val entries = MutableStateFlow(initial)
        val observations = mutableListOf<Pair<String, String>>()
        val adds = mutableListOf<Triple<String, String, String>>()
        val deletes = mutableListOf<Triple<String, String, String>>()
        val addCompletion = CompletableDeferred<Unit>()
        val deleteCompletion = CompletableDeferred<Unit>()
        var failRead = false

        override fun observe(bookId: String, contentSha256: String) = flow {
            observations += bookId to contentSha256
            if (failRead) throw IOException("Read failed")
            emitAll(entries)
        }

        override suspend fun add(bookId: String, contentSha256: String, text: String, anchor: com.yaprak.reader.reader.EpubSourceAnchor) {
            adds += Triple(bookId, contentSha256, text)
            addCompletion.await()
            entries.value = entries.value + EpubPassage("added", text, anchor, 2)
        }

        override suspend fun remove(bookId: String, contentSha256: String, id: String) {
            deletes += Triple(bookId, contentSha256, id)
            deleteCompletion.await()
            entries.value = entries.value.filterNot { it.id == id }
        }
        override suspend fun removeExpected(bookId: String, contentSha256: String, expected: EpubPassage) {
            check(entries.value.singleOrNull { it.id == expected.id } == expected)
            deletes += Triple(bookId, contentSha256, expected.id)
            deleteCompletion.await()
            check(entries.value.singleOrNull { it.id == expected.id } == expected)
            entries.value = entries.value.filterNot { it.id == expected.id }
        }
    }

    private suspend fun TestScope.withModel(
        repository: Repository,
        action: suspend (EpubPassagesViewModel) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val model = EpubPassagesViewModel("book-a", "hash-a", repository).also { owner.put("passages", it) }
            runCurrent()
            action(model)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `search and sort affect only saved display with no repository work`() = runTest {
        val source = listOf(passage("z", "Zeytin IŞIK"), passage("a", "Ada ışık"), passage("b", "Başka"))
        val repository = Repository(source)
        withModel(repository) { model ->
            val draft = passage("draft", "Aranan taslak")
            model.prefill(draft.text, draft.anchor)
            model.search("ışık")
            model.setSort(EpubPassageSort.TEXT_ASC)
            assertEquals(listOf("a", "z"), model.state.value.visibleEntries.map { it.id })
            assertEquals(source, model.state.value.entries)
            assertEquals(draft.text, model.state.value.draft?.text)
            model.search("Aranan taslak")
            assertTrue(model.state.value.visibleEntries.isEmpty())
            model.search("x".repeat(199) + "😀extra")
            assertEquals("x".repeat(199), model.state.value.query)
            model.clearSearch()
            assertEquals("", model.state.value.query)
            assertEquals(EpubPassageSort.TEXT_ASC, model.state.value.sort)
            assertEquals(listOf("a", "b", "z"), model.state.value.visibleEntries.map { it.id })
            runCurrent()
            assertEquals(listOf("book-a" to "hash-a"), repository.observations)
            assertTrue(repository.adds.isEmpty() && repository.deletes.isEmpty())
            assertEquals(source, repository.entries.value)
        }
    }

    @Test fun `live updates and retry keep filter while read errors retain full saved data`() = runTest {
        val first = passage("z", "Zeytin deniz")
        val second = passage("a", "Ada deniz")
        val hidden = passage("b", "Başka")
        val repository = Repository(listOf(first, hidden))
        withModel(repository) { model ->
            model.search("deniz")
            model.setSort(EpubPassageSort.TEXT_ASC)
            assertEquals(listOf(first), model.state.value.visibleEntries)
            repository.entries.value = listOf(first, hidden, second)
            runCurrent()
            assertEquals(listOf(second, first), model.state.value.visibleEntries)
            repository.failRead = true
            model.reload()
            runCurrent()
            assertTrue(model.state.value.loadFailed)
            assertFalse(model.state.value.loading)
            assertEquals(listOf(first, hidden, second), model.state.value.entries)
            assertEquals(listOf(second, first), model.state.value.visibleEntries)
            assertEquals("deniz", model.state.value.query)
            assertEquals(EpubPassageSort.TEXT_ASC, model.state.value.sort)
            repository.failRead = false
            repository.entries.value = listOf(hidden, second)
            model.reload()
            runCurrent()
            assertFalse(model.state.value.loadFailed || model.state.value.loading)
            assertEquals(listOf(second), model.state.value.visibleEntries)
            assertEquals("deniz", model.state.value.query)
            assertEquals(EpubPassageSort.TEXT_ASC, model.state.value.sort)
            assertEquals(3, repository.observations.size)
            assertTrue(repository.adds.isEmpty() && repository.deletes.isEmpty())
        }
    }

    @Test fun `saved anchor hidden by search still blocks duplicate selection`() = runTest {
        val saved = passage("saved", "Kayıtlı pasaj")
        val repository = Repository(listOf(saved))
        withModel(repository) { model ->
            model.search("eşleşmeyen")
            model.setSort(EpubPassageSort.TEXT_ASC)
            model.prefill(saved.text, saved.anchor)
            assertTrue(model.state.value.visibleEntries.isEmpty())
            assertEquals(listOf(saved), model.state.value.entries)
            assertTrue(model.state.value.alreadySaved)
            assertFalse(model.state.value.canSave)
            model.save()
            runCurrent()
            assertTrue(repository.adds.isEmpty())
            assertEquals("eşleşmeyen", model.state.value.query)
        }
    }

    @Test fun `session disposal resets search and sort before and after a pending failed write`() = runTest {
        val repository = Repository(emptyList())
        val draft = passage("draft", "Kaydet")
        withModel(repository) { model ->
            model.search("ilk")
            model.setSort(EpubPassageSort.TEXT_ASC)
            model.prefill(draft.text, draft.anchor)
            model.save()
            runCurrent()
            assertTrue(model.state.value.busy)
            model.clearSession()
            assertEquals("", model.state.value.query)
            assertEquals(EpubPassageSort.RECORD_ORDER, model.state.value.sort)
            assertNull(model.state.value.draft)
            model.search("geçici")
            model.setSort(EpubPassageSort.TEXT_ASC)
            repository.addCompletion.completeExceptionally(IOException("Write failed"))
            runCurrent()
            assertFalse(model.state.value.busy)
            assertEquals("", model.state.value.query)
            assertEquals(EpubPassageSort.RECORD_ORDER, model.state.value.sort)
            assertNull(model.state.value.draft)
            assertNull(model.state.value.message)
            assertEquals(listOf(Triple("book-a", "hash-a", draft.text)), repository.adds)
        }
    }

    @Test fun `filtered delete uses saved identity and book hash when query changes during confirmation`() = runTest {
        val hidden = passage("hidden", "Diğer")
        val selected = passage("selected", "Ada hedef")
        val other = passage("other", "Zeytin hedef")
        val repository = Repository(listOf(hidden, other, selected))
        withModel(repository) { model ->
            model.search("hedef")
            model.setSort(EpubPassageSort.TEXT_ASC)
            model.requestDelete(model.state.value.visibleEntries.first().id)
            assertEquals(selected, model.state.value.pendingDelete)
            model.search("diğer")
            assertEquals(listOf(hidden), model.state.value.visibleEntries)
            model.delete()
            model.delete()
            runCurrent()
            assertEquals(listOf(Triple("book-a", "hash-a", selected.id)), repository.deletes)
            repository.deleteCompletion.complete(Unit)
            runCurrent()
            assertEquals(listOf(hidden, other), model.state.value.entries)
            assertEquals(listOf(hidden), model.state.value.visibleEntries)
            assertNull(model.state.value.pendingDelete)
            assertEquals("diğer", model.state.value.query)
            assertEquals(EpubPassageSort.TEXT_ASC, model.state.value.sort)
        }
    }

    @Test fun `closing idle session resets display choices but preserves saved entries`() = runTest {
        val source = listOf(passage("z", "Zeytin"), passage("a", "Ada"))
        val repository = Repository(source)
        withModel(repository) { model ->
            model.search("ada")
            model.setSort(EpubPassageSort.TEXT_ASC)
            model.requestDelete("a")
            model.clearSession()
            assertEquals("", model.state.value.query)
            assertEquals(EpubPassageSort.RECORD_ORDER, model.state.value.sort)
            assertEquals(source, model.state.value.visibleEntries)
            assertEquals(source, model.state.value.entries)
            assertNull(model.state.value.pendingDelete)
            assertTrue(repository.adds.isEmpty() && repository.deletes.isEmpty())
        }
    }
}
