package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PdfPageLinksRequest(val source: PdfPageLinksSource, val pageIndex: Int)
internal data class PdfPageLinksState(
    val request: PdfPageLinksRequest? = null,
    val loading: Boolean = false,
    val links: List<PdfPageLink> = emptyList(),
    val resolvingId: String? = null,
    val failure: PdfPageLinksFailure? = null,
) { val open get() = request != null }

/** A modal lease belongs to one surface, page and foreground interval. Nothing is persisted. */
internal class PdfPageLinksController(private val scope: CoroutineScope, private val onChanged: () -> Unit = {}) {
    private val mutable = MutableStateFlow(PdfPageLinksState())
    val state = mutable.asStateFlow()
    private var current: () -> Boolean = { false }
    private var navigate: (PdfPageLinksRequest, ReadingLocation) -> Boolean = { _, _ -> false }
    private var job: Job? = null

    fun open(source: PdfPageLinksSource, pageIndex: Int, current: () -> Boolean,
        navigate: (PdfPageLinksRequest, ReadingLocation) -> Boolean): Boolean {
        if (pageIndex < 0 || !source.supported || !current()) return false
        close()
        this.current = current; this.navigate = navigate
        mutable.value = PdfPageLinksState(PdfPageLinksRequest(source, pageIndex), loading = true)
        onChanged(); load(mutable.value)
        return true
    }

    fun isCurrent(request: PdfPageLinksRequest): Boolean =
        mutable.value.request === request && current() && request.source.supported

    fun retry(expected: PdfPageLinksState) {
        if (mutable.value !== expected || expected.loading || expected.resolvingId != null) return
        val request = expected.request ?: return
        if (!isCurrent(request)) { close(request); return }
        job?.cancel()
        mutable.value = PdfPageLinksState(request, loading = true)
        onChanged(); load(mutable.value)
    }

    private fun load(expected: PdfPageLinksState) {
        val request = expected.request ?: return
        job = scope.launch {
            try {
                if (!isCurrent(request)) { close(request); return@launch }
                val links = request.source.load(request.pageIndex)
                ensureActive()
                if (!isCurrent(request)) { close(request); return@launch }
                mutable.value = PdfPageLinksState(request, links = links)
                onChanged()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(request, failure) }
        }
    }

    fun select(expected: PdfPageLinksState, linkId: String) {
        if (mutable.value !== expected || expected.loading || expected.resolvingId != null ||
            expected.links.none { it.id == linkId }) return
        val request = expected.request ?: return
        if (!isCurrent(request)) { close(request); return }
        mutable.value = expected.copy(resolvingId = linkId, failure = null)
        onChanged()
        job = scope.launch {
            try {
                if (!isCurrent(request)) { close(request); return@launch }
                val target = request.source.resolve(request.pageIndex, linkId)
                ensureActive()
                if (!isCurrent(request)) { close(request); return@launch }
                if (navigate(request, target)) close(request)
                else if (isCurrent(request)) {
                    mutable.value = mutable.value.copy(resolvingId = null, failure = PdfPageLinksFailure.NAVIGATION)
                    onChanged()
                } else close(request)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(request, failure) }
        }
    }

    private fun fail(request: PdfPageLinksRequest, failure: Exception) {
        if (mutable.value.request !== request) return
        if (!isCurrent(request)) { close(request); return }
        mutable.value = mutable.value.copy(loading = false, resolvingId = null,
            failure = (failure as? PdfPageLinksException)?.reason ?: PdfPageLinksFailure.SOURCE)
        onChanged()
    }

    fun close(expected: PdfPageLinksRequest? = null) {
        if (expected != null && mutable.value.request !== expected) return
        job?.cancel(); job = null
        current = { false }; navigate = { _, _ -> false }
        mutable.value = PdfPageLinksState()
        onChanged()
    }
}
