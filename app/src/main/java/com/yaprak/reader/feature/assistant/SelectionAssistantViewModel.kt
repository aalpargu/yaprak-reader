package com.yaprak.reader.feature.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.assistant.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SelectionAssistantState(val request: SelectedTextSourceRequest,
    val question: String, val endpoint: String = "", val accessToken: String = "",
    val preparing: Boolean = false, val sending: Boolean = false, val revision: Long = 0,
    val sources: PreparedSelectedTextSources? = null, val answer: SelectedTextAnswer? = null,
    val failure: BookAnswerFailure? = null) {
    val busy get() = preparing || sending
    val canSend get() = !busy && sources != null && question.isNotBlank()
    override fun toString() = "SelectionAssistantState(revision=$revision, preparing=$preparing, sending=$sending)"
}

/** Ephemeral selection ownership; only an explicit send calls the service. No SavedState or disk. */
internal class SelectionAssistantViewModel(private val provider: SelectedTextSourceProvider,
    private val service: SelectedTextAnswerService) : ViewModel() {
    private val mutable = MutableStateFlow<SelectionAssistantState?>(null)
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var serial = 0L

    fun open(request: SelectedTextSourceRequest, question: String, isCurrent: () -> Boolean) {
        if (!request.isValid || !validAnswerText(question, MAX_BOOK_QUESTION) || !isCurrent()) return
        close()
        val initial = SelectionAssistantState(request, question, revision = ++serial)
        mutable.value = initial
        prepare(initial, isCurrent)
    }
    fun close() {
        ++serial; operation?.cancel(); operation = null; mutable.value = null
    }
    fun close(expected: SelectionAssistantState) { if (state.value === expected) close() }
    fun editQuestion(expected: SelectionAssistantState, value: String) {
        if (value.length <= MAX_BOOK_QUESTION) edit(expected) { it.copy(question = value) }
    }
    fun editEndpoint(expected: SelectionAssistantState, value: String) {
        if (value.length <= 2048) edit(expected) { it.copy(endpoint = value) }
    }
    fun editToken(expected: SelectionAssistantState, value: String) {
        if (value.length <= 256) edit(expected) { it.copy(accessToken = value) }
    }
    private fun edit(expected: SelectionAssistantState, change: (SelectionAssistantState) -> SelectionAssistantState) {
        if (state.value !== expected || expected.busy) return
        mutable.value = change(expected).copy(answer = null, failure = null, revision = ++serial)
    }

    fun prepare(expected: SelectionAssistantState, isCurrent: () -> Boolean) {
        if (state.value !== expected || expected.busy || !isCurrent()) return
        start(expected, preparing = true, isCurrent) { checkCurrent ->
            val sources = provider.prepare(expected.request)
            checkCurrent()
            requireSources(expected.request, sources)
            mutable.value = state.value!!.copy(sources = sources)
        }
    }

    fun send(expected: SelectionAssistantState, isCurrent: () -> Boolean) {
        if (state.value !== expected || !expected.canSend || !isCurrent()) return
        val connection = try {
            SelectedTextQuestionRequest.create(expected.sources!!, expected.question)
            BookAnswerConnection.create(expected.endpoint, expected.accessToken)
        } catch (failure: BookAnswerException) {
            mutable.value = expected.copy(failure = failure.reason, answer = null, revision = ++serial)
            return
        }
        start(expected, preparing = false, isCurrent) { checkCurrent ->
            val fresh = provider.prepare(expected.request)
            checkCurrent()
            requireSources(expected.request, fresh)
            if (!fresh.sameEvidence(expected.sources!!)) failAnswer(BookAnswerFailure.SOURCE)
            val request = SelectedTextQuestionRequest.create(fresh, expected.question)
            checkCurrent() // Guard after real source IO, immediately before transmission.
            mutable.value = state.value!!.copy(sources = fresh)
            val answer = try { service.answerSelection(request, connection) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: BookAnswerException) { throw failure }
            catch (_: Exception) { failAnswer(BookAnswerFailure.INVALID_RESPONSE) }
            checkCurrent()
            validateSelectedTextAnswer(request, answer)
            mutable.value = state.value!!.copy(answer = answer)
        }
    }

    private fun requireSources(request: SelectedTextSourceRequest, sources: PreparedSelectedTextSources) {
        if (sources.request != request) failAnswer(BookAnswerFailure.SOURCE)
        SelectedTextQuestionRequest.create(sources, "Kaynak doğrulaması")
    }
    private fun start(expected: SelectionAssistantState, preparing: Boolean, isCurrent: () -> Boolean,
        block: suspend (checkCurrent: () -> Unit) -> Unit) {
        operation?.cancel()
        val ticket = ++serial
        mutable.value = expected.copy(preparing = preparing, sending = !preparing, revision = ticket,
            sources = if (preparing) null else expected.sources, answer = null, failure = null)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val context = currentCoroutineContext()
                val checkCurrent = {
                    context.ensureActive()
                    if (serial != ticket || state.value?.request != expected.request || !isCurrent())
                        throw CancellationException("Selection owner is no longer active")
                }
                checkCurrent(); block(checkCurrent)
            } catch (cancelled: CancellationException) {
                if (serial == ticket) mutable.value = null
                throw cancelled
            } catch (failure: Exception) {
                if (serial == ticket && state.value?.request == expected.request) {
                    if (!isCurrent()) mutable.value = null
                    else {
                        val reason = (failure as? BookAnswerException)?.reason ?: BookAnswerFailure.SOURCE
                        mutable.value = state.value!!.copy(failure = reason, answer = null,
                            sources = if (reason == BookAnswerFailure.SOURCE) null else state.value!!.sources)
                    }
                }
            } finally {
                if (serial == ticket) {
                    operation = null
                    mutable.value = state.value?.copy(preparing = false, sending = false)
                }
            }
        }
        operation = job; job.start()
    }
}
