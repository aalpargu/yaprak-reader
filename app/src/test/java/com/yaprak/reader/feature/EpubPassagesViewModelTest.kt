package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.domain.passages.EpubPassageRepository
import com.yaprak.reader.feature.passages.EpubPassagesViewModel
import com.yaprak.reader.reader.SourceTextAnchor
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpubPassagesViewModelTest {
    private val anchor = SourceTextAnchor("chapter.xhtml", 1, 5, "a".repeat(64))
    private val passage = EpubPassage("passage-1", "Leaf", anchor, 1)

    private class Repository : EpubPassageRepository {
        val entries = MutableStateFlow<List<EpubPassage>>(emptyList())
        var failRead = false
        var failWrite = false
        var emitAdd = true
        var adds = 0
        var deletes = 0
        var added: Triple<String, String, String>? = null
        var addedAnchor: com.yaprak.reader.reader.EpubSourceAnchor? = null
        var deletedId: String? = null

        override fun observe(bookId: String, contentSha256: String) = flow {
            if (failRead) throw IOException("read")
            emitAll(entries)
        }

        override suspend fun add(bookId: String, contentSha256: String, text: String, anchor: com.yaprak.reader.reader.EpubSourceAnchor) {
            adds++
            added = Triple(bookId, contentSha256, text)
            addedAnchor = anchor
            delay(100)
            if (failWrite) throw IOException("write")
            if (emitAdd) entries.value = entries.value + EpubPassage("new-$adds", text, anchor, adds.toLong())
        }

        override suspend fun remove(bookId: String, contentSha256: String, id: String) {
            deletes++
            deletedId = id
            delay(100)
            if (failWrite) throw IOException("delete")
            entries.value = entries.value.filterNot { it.id == id }
        }

        override suspend fun removeExpected(bookId: String, contentSha256: String, expected: EpubPassage) {
            check(entries.value.singleOrNull { it.id == expected.id } == expected)
            deletes++
            deletedId = expected.id
            delay(100)
            if (failWrite) throw IOException("delete")
            check(entries.value.singleOrNull { it.id == expected.id } == expected)
            entries.value = entries.value.filterNot { it.id == expected.id }
        }
    }

    @Test fun `source matched selection is only a draft until explicit save and failed save is retryable`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failWrite = true }
            val model = EpubPassagesViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.prefill("Leaf", anchor)
            assertTrue(model.state.value.canSave)
            assertEquals(0, repository.adds)
            assertTrue(model.state.value.entries.isEmpty())
            model.save()
            model.save()
            runCurrent()
            assertTrue(model.state.value.busy)
            assertEquals(1, repository.adds)
            advanceUntilIdle()
            assertEquals("Leaf", model.state.value.draft?.text)
            assertEquals("Pasaj kaydedilemedi. Yeniden deneyebilirsin.", model.state.value.message)
            assertTrue(model.state.value.entries.isEmpty())
            repository.failWrite = false
            model.save()
            advanceUntilIdle()
            assertEquals(2, repository.adds)
            assertEquals(Triple("book-a", "hash-a", "Leaf"), repository.added)
            assertEquals(anchor, repository.addedAnchor)
            assertNull(model.state.value.draft)
            assertEquals(listOf("Leaf"), model.state.value.entries.map { it.text })
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `invalid or duplicate selection cannot save and closing discards transient draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { entries.value = listOf(passage) }
            val model = EpubPassagesViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.prefill(" ", anchor)
            model.prefill("Leaf", anchor.copy(end = 6))
            model.prefill("L".repeat(4097), anchor.copy(end = 4098))
            assertNull(model.state.value.draft)
            model.prefill("Leaf", anchor)
            assertTrue(model.state.value.alreadySaved)
            assertFalse(model.state.value.canSave)
            model.save()
            assertEquals(0, repository.adds)
            model.clearSession()
            assertNull(model.state.value.draft)
            assertFalse(model.state.value.alreadySaved)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `delete needs confirmation and failed delete keeps entry for retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { entries.value = listOf(passage); failWrite = true }
            val model = EpubPassagesViewModel("book-b", "hash-b", repository).also { owner.put("vm", it) }
            runCurrent()
            model.requestDelete("missing")
            assertNull(model.state.value.pendingDelete)
            model.requestDelete(passage.id)
            model.cancelDelete()
            model.delete()
            assertEquals(0, repository.deletes)
            model.requestDelete(passage.id)
            model.delete()
            model.delete()
            runCurrent()
            assertEquals(1, repository.deletes)
            advanceUntilIdle()
            assertEquals(passage, model.state.value.pendingDelete)
            assertEquals(listOf(passage), model.state.value.entries)
            repository.failWrite = false
            model.delete()
            advanceUntilIdle()
            assertEquals(2, repository.deletes)
            assertEquals(passage.id, repository.deletedId)
            assertNull(model.state.value.pendingDelete)
            assertTrue(model.state.value.entries.isEmpty())
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `read failure blocks save and reload retains unsaved selection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failRead = true }
            val model = EpubPassagesViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.prefill("Leaf", anchor)
            assertTrue(model.state.value.loadFailed)
            assertFalse(model.state.value.canSave)
            model.save()
            assertEquals(0, repository.adds)
            repository.failRead = false
            model.reload()
            runCurrent()
            assertTrue(model.state.value.canSave)
            assertEquals("Leaf", model.state.value.draft?.text)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `disposing route during failed save discards draft after write settles`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failWrite = true }
            val model = EpubPassagesViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.prefill("Leaf", anchor)
            model.save()
            runCurrent()
            assertTrue(model.state.value.busy)
            model.clearSession() // Composition disposed during rotation; do not cancel the explicit write.
            assertNull(model.state.value.draft)
            advanceUntilIdle()
            assertEquals(1, repository.adds)
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.draft)
            assertNull(model.state.value.message)
            assertTrue(model.state.value.entries.isEmpty())
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `reopening saved selection stays duplicate even before Room observation arrives`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { emitAdd = false }
            val model = EpubPassagesViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.prefill("Leaf", anchor)
            model.save()
            advanceUntilIdle()
            assertTrue(model.state.value.entries.isEmpty())
            model.clearSession()
            model.prefill("Leaf", anchor)
            assertTrue(model.state.value.alreadySaved)
            assertFalse(model.state.value.canSave)
            model.save()
            assertEquals(1, repository.adds)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
}
