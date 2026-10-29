package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as A
import com.yaprak.reader.fixtures.SelectedAssistantFixtures as F
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectionAssistantViewModelTest {
    private suspend fun TestScope.withModel(provider: SelectedTextSourceProvider = SelectedTextSourceProvider { F.sources(it) },
        service: SelectedTextAnswerService = SelectedTextAnswerService { request, _ -> F.answer(request) },
        block: suspend TestScope.(SelectionAssistantViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val model = SelectionAssistantViewModel(provider, service).also { store.put("selected", it) }
        try { block(model) } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private fun configure(model: SelectionAssistantViewModel) {
        model.editEndpoint(model.state.value!!, A.connection().endpoint.toString())
        model.editToken(model.state.value!!, A.connection().accessToken)
    }
    @Test fun openAndTypingNeverSendAndSendReprovesWithNewUuid() = runTest {
        var proofs = 0; val sent = mutableListOf<SelectedTextQuestionRequest>()
        withModel(SelectedTextSourceProvider { proofs++; F.sources(it) }, SelectedTextAnswerService { request, _ -> sent += request; F.answer(request) }) { model ->
            assertNull(model.state.value)
            model.open(F.request(), "Açıkla") { true }; runCurrent()
            val preview = model.state.value!!.sources!!
            assertEquals(1, proofs); assertTrue(sent.isEmpty()); configure(model)
            model.editQuestion(model.state.value!!, "Daha basit anlat")
            val admitted = model.state.value!!
            model.send(admitted) { true }; model.send(admitted) { true }; runCurrent()
            assertEquals(2, proofs); assertEquals(1, sent.size)
            assertNotEquals(preview.requestId, sent.single().sources.requestId)
            assertEquals("Daha basit anlat", sent.single().question)
            assertNotNull(model.state.value!!.answer)
            assertFalse(model.state.value.toString().contains(F.text))
        }
    }
    @Test fun missingConfigurationDoesNotReproveOrSend() = runTest {
        var proofs = 0; var sends = 0
        withModel(SelectedTextSourceProvider { proofs++; F.sources(it) }, SelectedTextAnswerService { _, _ -> sends++; error("unexpected") }) { model ->
            model.open(F.request(), "Soru") { true }; runCurrent()
            model.send(model.state.value!!) { true }; runCurrent()
            assertEquals(1, proofs); assertEquals(0, sends); assertEquals(BookAnswerFailure.CONFIGURATION, model.state.value!!.failure)
        }
    }
    @Test fun changedMetadataBeforeSendRequiresAnotherPreview() = runTest {
        var proofs = 0; var sends = 0
        withModel(SelectedTextSourceProvider { request -> F.sources(request).let { if (++proofs == 1) it else
            PreparedSelectedTextSources(request, F.book.copy(title = "Changed"), it.references) } },
            SelectedTextAnswerService { _, _ -> sends++; error("unexpected") }) { model ->
            model.open(F.request(), "Soru") { true }; runCurrent(); configure(model)
            model.send(model.state.value!!) { true }; runCurrent()
            assertEquals(0, sends); assertEquals(BookAnswerFailure.SOURCE, model.state.value!!.failure)
            assertNull(model.state.value!!.sources)
        }
    }
    @Test fun cancelledOldPreparationCannotReplaceRepeatedSelection() = runTest {
        val late = CompletableDeferred<Unit>(); var first = true
        withModel(SelectedTextSourceProvider { request -> if (first) { first = false; withContext(NonCancellable) { late.await() } }; F.sources(request) }) { model ->
            val old = F.request(); model.open(old, "Old") { true }; runCurrent()
            val current = F.request(); model.open(current, "New") { true }; runCurrent()
            late.complete(Unit); runCurrent()
            assertEquals(current, model.state.value!!.request); assertEquals("New", model.state.value!!.question)
        }
    }
    @Test fun ownerBecomesInactiveDuringReproofNeverSends() = runTest {
        val late = CompletableDeferred<Unit>(); var proofs = 0; var sends = 0; var current = true
        withModel(SelectedTextSourceProvider { request -> if (++proofs == 2) late.await(); F.sources(request) },
            SelectedTextAnswerService { _, _ -> sends++; error("unexpected") }) { model ->
            model.open(F.request(), "Soru") { current }; runCurrent(); configure(model)
            model.send(model.state.value!!) { current }; runCurrent()
            current = false; late.complete(Unit); runCurrent()
            assertEquals(0, sends); assertNull(model.state.value)
        }
    }
    @Test fun closeDropsLateAnswerAndCredentials() = runTest {
        val late = CompletableDeferred<Unit>()
        withModel(service = SelectedTextAnswerService { request, _ -> withContext(NonCancellable) { late.await() }; F.answer(request) }) { model ->
            model.open(F.request(), "Soru") { true }; runCurrent(); configure(model)
            model.send(model.state.value!!) { true }; runCurrent(); assertTrue(model.state.value!!.sending)
            model.close(); late.complete(Unit); runCurrent(); assertNull(model.state.value)
        }
    }
    @Test fun oldStateButtonsAndAbaCannotEditSendOrCloseNewState() = runTest {
        var sends = 0
        withModel(service = SelectedTextAnswerService { request, _ -> sends++; F.answer(request) }) { model ->
            model.open(F.request(), "Soru") { true }; runCurrent(); configure(model)
            val old = model.state.value!!
            model.editQuestion(old, "Başka"); model.editQuestion(model.state.value!!, "Soru")
            model.send(old) { true }; model.close(old); model.editToken(old, "wrong")
            assertEquals(0, sends); assertEquals("Soru", model.state.value!!.question)
            assertEquals(A.connection().accessToken, model.state.value!!.accessToken)
        }
    }
    @Test fun forgedAnswerNeverDisplaysAndExplicitRetryIsRequired() = runTest {
        var calls = 0
        withModel(service = SelectedTextAnswerService { _, _ -> calls++; SelectedTextAnswer("foreign", BookAnswerStatus.INSUFFICIENT, emptyList(), "X") }) { model ->
            model.open(F.request(), "Soru") { true }; runCurrent(); configure(model)
            model.send(model.state.value!!) { true }; runCurrent()
            assertEquals(1, calls); assertNull(model.state.value!!.answer)
            assertEquals(BookAnswerFailure.INVALID_RESPONSE, model.state.value!!.failure)
            runCurrent(); assertEquals(1, calls)
        }
    }
    @Test fun unexpectedServiceFailureDoesNotClaimSourceWasNeverSent() = runTest {
        var calls = 0
        withModel(service = SelectedTextAnswerService { _, _ -> calls++; error("Response boundary failed") }) { model ->
            model.open(F.request(), "Soru") { true }; runCurrent(); configure(model)
            model.send(model.state.value!!) { true }; runCurrent()
            assertEquals(1, calls); assertNull(model.state.value!!.answer)
            assertEquals(BookAnswerFailure.INVALID_RESPONSE, model.state.value!!.failure)
            assertNotNull(model.state.value!!.sources)
            runCurrent(); assertEquals(1, calls)
        }
    }
    @Test fun invalidOrInactiveEntryNeverPrepares() = runTest {
        var proofs = 0
        withModel(SelectedTextSourceProvider { proofs++; F.sources(it) }) { model ->
            model.open(F.request(), "Soru") { false }
            model.open(F.request().copy(selectionId = "old"), "Soru") { true }; runCurrent()
            assertEquals(0, proofs); assertNull(model.state.value)
        }
    }
}
