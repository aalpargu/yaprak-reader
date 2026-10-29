package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.reader.BookContents
import com.yaprak.reader.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ContentsRequest(val source: ContentsNavigator)
internal data class ContentsState(val request: ContentsRequest? = null, val loading: Boolean = false,
    val contents: BookContents? = null, val failure: ContentsLoadFailure? = null)

/** Main-confined modal request; stale loads and callbacks cannot replace another surface's contents. */
internal class ContentsController(private val scope: CoroutineScope, private val onOpenChanged: (Boolean) -> Unit = {}) {
    private val mutable = MutableStateFlow(ContentsState())
    val state = mutable.asStateFlow()
    private var current: () -> Boolean = { false }
    private var job: Job? = null

    private fun publish(state: ContentsState) {
        val wasOpen = mutable.value.request != null
        mutable.value = state
        val open = state.request != null
        if (wasOpen != open) onOpenChanged(open)
    }

    fun open(source: ContentsNavigator, current: () -> Boolean) {
        close()
        if (!current()) return
        this.current = current
        load(ContentsRequest(source))
    }

    fun isCurrent(request: ContentsRequest): Boolean = mutable.value.request === request && current()

    fun retry(expected: ContentsState) {
        val request = expected.request ?: return
        if (mutable.value !== expected || expected.loading || !isCurrent(request)) return
        job?.cancel(); load(request)
    }

    private fun load(request: ContentsRequest) {
        publish(ContentsState(request, loading = true))
        job = scope.launch {
            try {
                if (!isCurrent(request)) { close(request); return@launch }
                val result = request.source.loadContents()
                ensureActive()
                if (isCurrent(request)) publish(ContentsState(request, contents = result))
                else close(request)
            } catch (cancelled: CancellationException) { close(request); throw cancelled }
            catch (failure: Exception) {
                if (isCurrent(request)) publish(ContentsState(request,
                    failure = (failure as? ContentsLoadException)?.reason ?: ContentsLoadFailure.SOURCE))
                else close(request)
            }
        }
    }

    fun canSelect(expected: ContentsState, id: String): Boolean = mutable.value === expected && !expected.loading &&
        expected.failure == null && expected.request?.let(::isCurrent) == true &&
        expected.contents?.entries?.any { it.id == id && it.navigable } == true

    fun close(expected: ContentsRequest? = null) {
        if (expected != null && mutable.value.request !== expected) return
        job?.cancel(); job = null; current = { false }; publish(ContentsState())
    }
}
