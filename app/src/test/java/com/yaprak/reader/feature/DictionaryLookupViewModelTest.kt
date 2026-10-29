package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.dictionary.*
import com.yaprak.reader.feature.dictionary.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryLookupViewModelTest {
    private fun result(word: String) = DictionaryResult(word, listOf(DictionaryEntry(word, null,
        listOf(DictionaryDefinition("noun", "Controlled definition.", null)), null, emptyList())))
    private suspend fun TestScope.withModel(dictionary: EnglishDictionary, block: suspend TestScope.(DictionaryLookupViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val model = DictionaryLookupViewModel(dictionary).also { store.put("dictionary", it) }
        try { block(model) } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun `explicit normalized lookup only and invalid paragraph is visible without a provider call`() = runTest {
        val calls = mutableListOf<String>()
        withModel(EnglishDictionary { calls += it; result(it) }) { model ->
            runCurrent(); assertNull(model.state.value); assertTrue(calls.isEmpty())
            assertTrue(model.lookup("selection-one", "two words")); assertEquals(DictionaryFailure.INVALID_WORD, model.state.value!!.failure)
            runCurrent(); assertTrue(calls.isEmpty())
            model.lookup("selection-one", " LEAF "); assertTrue(model.state.value!!.loading); runCurrent()
            assertEquals(listOf("leaf"), calls); assertEquals(result("leaf"), model.state.value!!.result)
        }
    }
    @Test fun `failure preserves exact query with fresh explicit retry and obsolete retry cannot retarget`() = runTest {
        var failing = true; val calls = mutableListOf<String>()
        withModel(EnglishDictionary { calls += it; if (failing) throw DictionaryException(DictionaryFailure.NETWORK); result(it) }) { model ->
            model.lookup("selection-one", "Leaf"); runCurrent()
            val failed = model.state.value!!; assertEquals(DictionaryFailure.NETWORK, failed.failure); assertNull(failed.result)
            failing = false; assertTrue(model.retry(failed)); assertFalse(model.retry(failed)); runCurrent()
            val completed = model.state.value!!; assertNotEquals(failed.attemptId, completed.attemptId)
            assertEquals(listOf("leaf", "leaf"), calls); assertEquals(result("leaf"), completed.result)
            model.close(); model.lookup("selection-one", "leaf"); runCurrent()
            assertFalse(model.retry(failed)); assertFalse(model.retry(completed))
        }
    }
    @Test fun `close and replacement reject cancellation ignoring late success for same selection UUID`() = runTest {
        val first = CompletableDeferred<DictionaryResult>(); val second = CompletableDeferred<DictionaryResult>(); var calls = 0
        withModel(EnglishDictionary { if (++calls == 1) withContext(NonCancellable) { first.await() } else second.await() }) { model ->
            model.lookup("selection-one", "leaf"); runCurrent(); assertFalse(model.lookup("selection-one", "leaf"))
            model.close(); assertNull(model.state.value)
            model.lookup("selection-one", "book"); runCurrent(); val current = model.state.value!!
            first.complete(result("leaf")); runCurrent(); assertSame(current, model.state.value)
            second.complete(result("book")); runCurrent(); assertEquals(result("book"), model.state.value!!.result)
        }
    }
    @Test fun `late failure cannot replace new result or closed owner`() = runTest {
        val late = CompletableDeferred<Unit>(); var calls = 0
        withModel(EnglishDictionary { if (++calls == 1) withContext(NonCancellable) { late.await(); throw DictionaryException(DictionaryFailure.TIMEOUT) }; result(it) }) { model ->
            model.lookup("old-selection", "leaf"); runCurrent(); model.lookup("new-selection", "book"); runCurrent()
            val current = model.state.value!!; late.complete(Unit); runCurrent(); assertSame(current, model.state.value)
            model.close(); runCurrent(); assertNull(model.state.value)
        }
    }
    @Test fun `provider cancellation is not converted to failure and lookup can be explicitly retried`() = runTest {
        var calls = 0
        withModel(EnglishDictionary { if (++calls == 1) throw CancellationException(); result(it) }) { model ->
            model.lookup("selection-one", "leaf"); runCurrent(); assertNull(model.state.value)
            assertTrue(model.lookup("selection-one", "leaf")); runCurrent(); assertEquals(result("leaf"), model.state.value!!.result)
        }
    }
    @Test fun `mismatched or empty provider result is never displayed as an answer`() = runTest {
        withModel(EnglishDictionary { result("other-word") }) { model ->
            model.lookup("selection-one", "leaf"); runCurrent(); assertEquals(DictionaryFailure.PROVIDER, model.state.value!!.failure); assertNull(model.state.value!!.result)
        }
        withModel(EnglishDictionary { DictionaryResult(it, emptyList()) }) { model ->
            model.lookup("selection-one", "leaf"); runCurrent(); assertEquals(DictionaryFailure.PROVIDER, model.state.value!!.failure)
        }
    }
}
