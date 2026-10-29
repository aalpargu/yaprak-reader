package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookAssistantViewModelTest {
    private class Index : LibraryTextIndex {
        val book = MutableStateFlow<Book?>(F.book)
        var resolve: suspend (LibraryTextHit) -> LibraryTextHit? = { it }
        override fun observe(query: String) = flowOf(LibraryTextSearchResult(query, emptyList(), 0, emptyList()))
        override fun observeBook(query: String, scope: BookTextScope) = book.map { value ->
            LibraryTextSearchResult(query, emptyList(), 0, listOfNotNull(value?.let { LibraryTextBookStatus(it, LibraryTextBookState.READY) }), scope)
        }
        override suspend fun rebuild(onProgress: (LibraryTextIndexProgress) -> Unit) = Unit
        override suspend fun resolve(hit: LibraryTextHit) = resolve.invoke(hit)
    }
    private suspend fun TestScope.withModel(retriever: BookSourceRetriever = BookSourceRetriever { _, query -> F.sources(query = query) },
        service: BookAnswerService = BookAnswerService { request, _ -> F.answer(request) },
        block: suspend TestScope.(BookAssistantViewModel, Index, Any) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); val index = Index(); val owner = Any()
        val model = BookAssistantViewModel(F.scope, "Güneş", retriever, index, service).also { store.put("assistant", it) }
        try { model.attachOwner(owner); model.setOwnerActive(owner, true); runCurrent(); block(model, index, owner) }
        finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private fun configure(model: BookAssistantViewModel) {
        model.editQuestion("Ne oldu?"); model.editEndpoint(F.connection().endpoint.toString()); model.editToken(F.connection().accessToken)
    }
    private suspend fun TestScope.prepare(model: BookAssistantViewModel) { model.prepare(model.state.value.revision); runCurrent() }

    @Test fun `entry typing preparation and resume never call service and send reproves preview with a new UUID`() = runTest {
        var proofs = 0; val requests = mutableListOf<BookQuestionRequest>()
        withModel(BookSourceRetriever { _, query -> proofs++; F.sources(query = query) }, BookAnswerService { request, _ -> requests += request; F.answer(request) }) { model, _, _ ->
            assertEquals(0, proofs); assertTrue(requests.isEmpty()); configure(model); runCurrent(); assertTrue(requests.isEmpty())
            prepare(model); val preview = model.state.value.sources!!; assertEquals(1, proofs); assertTrue(requests.isEmpty())
            val revision = model.state.value.revision; model.send(revision); model.send(revision); runCurrent()
            assertEquals(2, proofs); assertEquals(1, requests.size)
            assertNotEquals(preview.requestId, requests.single().sources.requestId)
            assertEquals(requests.single().sources.requestId, model.state.value.answer!!.requestId)
            assertFalse(model.state.value.toString().contains(model.state.value.accessToken))
            assertFalse(model.state.value.toString().contains(model.state.value.question))
        }
    }
    @Test fun `unconfigured no-source and invalid question never transmit and retry is an explicit send`() = runTest {
        var calls = 0
        withModel(service = BookAnswerService { request, _ -> calls++; F.answer(request) }) { model, _, _ ->
            model.editQuestion("Soru"); prepare(model); model.send(model.state.value.revision); runCurrent()
            assertEquals(BookAnswerFailure.CONFIGURATION, model.state.value.failure); assertEquals(0, calls)
            configure(model); model.editQuestion("bad\u0000"); model.send(model.state.value.revision); runCurrent()
            assertEquals(BookAnswerFailure.INVALID_INPUT, model.state.value.failure); assertEquals(0, calls)
            model.editQuestion("Soru"); model.send(model.state.value.revision); runCurrent(); assertEquals(1, calls)
        }
        withModel(BookSourceRetriever { _, query -> PreparedBookSources(java.util.UUID.randomUUID().toString(), F.scope, query, 0, emptyList()) },
            BookAnswerService { request, _ -> calls++; F.answer(request) }) { model, _, _ ->
            configure(model); prepare(model); assertFalse(model.state.value.canSend); model.send(model.state.value.revision); runCurrent(); assertEquals(1, calls)
        }
    }
    @Test fun `changed fresh sources must be previewed again and cannot silently send a different paragraph`() = runTest {
        var proofs = 0; var calls = 0
        withModel(BookSourceRetriever { _, query -> F.sources(if (++proofs == 1) F.hit else F.hit.copy(text = "Güneş battı."), query) },
            BookAnswerService { request, _ -> calls++; F.answer(request) }) { model, _, _ ->
            configure(model); prepare(model); model.send(model.state.value.revision); runCurrent()
            assertEquals(BookSourceFailure.SOURCE, model.state.value.sourceFailure); assertNull(model.state.value.sources)
            assertNull(model.state.value.answer); assertEquals(0, calls)
        }
    }
    @Test fun `pause replacement ownership and cancellation ignoring answer never publish into a resumed request`() = runTest {
        val late = CompletableDeferred<BookAnswer>(); var old: BookQuestionRequest? = null; var calls = 0
        withModel(service = BookAnswerService { request, _ -> if (++calls == 1) { old = request; withContext(NonCancellable) { late.await() } } else F.answer(request) }) { model, _, owner ->
            configure(model); prepare(model); model.send(model.state.value.revision); runCurrent(); assertTrue(model.state.value.busy)
            model.setOwnerActive(owner, false); assertNull(model.state.value.sources); assertNull(model.state.value.answer)
            val replacement = Any(); model.attachOwner(replacement); model.setOwnerActive(replacement, true); runCurrent()
            model.detachOwner(owner); assertTrue(model.isActive(replacement)); prepare(model)
            model.send(model.state.value.revision); runCurrent(); val answer = model.state.value.answer!!
            late.complete(F.answer(old!!)); runCurrent(); assertSame(answer, model.state.value.answer); assertEquals(2, calls)
        }
    }
    @Test fun `book metadata deletion or content replacement cancels provider and drops citations`() = runTest {
        val late = CompletableDeferred<Unit>(); var request: BookQuestionRequest? = null
        withModel(service = BookAnswerService { req, _ -> request = req; withContext(NonCancellable) { late.await() }; F.answer(req) }) { model, index, _ ->
            configure(model); prepare(model); model.send(model.state.value.revision); runCurrent()
            index.book.value = F.book.copy(title = "Yeni başlık"); runCurrent()
            assertFalse(model.state.value.busy); assertNull(model.state.value.sources); assertEquals(BookSourceFailure.SOURCE, model.state.value.sourceFailure)
            late.complete(Unit); runCurrent(); assertNotNull(request); assertNull(model.state.value.answer)
            index.book.value = F.book.copy(sha256 = "c".repeat(64)); runCurrent(); assertNull(model.state.value.book)
        }
    }
    @Test fun `obsolete send prepare and citation actions are rejected after revisions and source resolve`() = runTest {
        var calls = 0; val opened = mutableListOf<LibraryTextHit>()
        withModel(service = BookAnswerService { request, _ -> calls++; F.answer(request) }) { model, index, _ ->
            configure(model); val old = model.state.value.revision; model.editKeywords("Yeni"); model.prepare(old); runCurrent(); assertNull(model.state.value.sources)
            model.editKeywords("Güneş"); prepare(model); val oldSend = model.state.value.revision
            model.editQuestion("Başka soru"); model.send(oldSend); runCurrent(); assertEquals(0, calls)
            model.send(model.state.value.revision); runCurrent()
            val answer = model.state.value.answer!!; val citation = answer.statements.single().citations.single(); val revision = model.state.value.revision
            index.resolve = { null }; model.open(citation, revision) { opened += it }; runCurrent()
            assertTrue(opened.isEmpty()); assertEquals(BookSourceFailure.SOURCE, model.state.value.sourceFailure)
            model.open(citation, revision) { opened += it }; runCurrent(); assertTrue(opened.isEmpty())
            prepare(model); model.send(model.state.value.revision); runCurrent()
            val fresh = model.state.value.answer!!.statements.single().citations.single(); index.resolve = { it }
            model.open(fresh, model.state.value.revision) { opened += it }; runCurrent(); assertEquals(listOf(F.hit), opened)
        }
    }
    @Test fun `cancellation does not become provider error and retry never happens automatically`() = runTest {
        var calls = 0
        withModel(service = BookAnswerService { request, _ -> if (++calls == 1) throw CancellationException(); F.answer(request) }) { model, _, _ ->
            configure(model); prepare(model); model.send(model.state.value.revision); runCurrent()
            assertFalse(model.state.value.busy); assertNull(model.state.value.failure); assertNull(model.state.value.answer); assertEquals(1, calls)
            model.send(model.state.value.revision); runCurrent(); assertEquals(2, calls); assertNotNull(model.state.value.answer)
        }
    }
    @Test fun `wrong request service answer is rejected even when source quotes look correct`() = runTest {
        withModel(service = BookAnswerService { _, _ -> F.answer(F.request()) }) { model, _, _ ->
            configure(model); prepare(model); model.send(model.state.value.revision); runCurrent()
            assertEquals(BookAnswerFailure.INVALID_RESPONSE, model.state.value.failure); assertNull(model.state.value.answer)
        }
    }
}
