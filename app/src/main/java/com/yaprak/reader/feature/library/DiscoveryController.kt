package com.yaprak.reader.feature.library

import androidx.lifecycle.SavedStateHandle
import com.yaprak.reader.domain.library.BookCandidate
import com.yaprak.reader.domain.library.BookDiscovery
import com.yaprak.reader.domain.library.DiscoveryFailure
import com.yaprak.reader.domain.library.DiscoveryLimit
import com.yaprak.reader.domain.library.DiscoveryProgress
import com.yaprak.reader.domain.library.DiscoveryResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DiscoveryState {
    data object Idle : DiscoveryState
    data class Choosing(val sessionId: String) : DiscoveryState
    data class Scanning(val sessionId: String, val progress: DiscoveryProgress = DiscoveryProgress(0, 0),
        val cancelRequested: Boolean = false) : DiscoveryState
    data class Preview(val sessionId: String, val candidates: List<BookCandidate>, val visited: Int,
        val limit: DiscoveryLimit? = null, val query: String = "", val selectedIds: Set<String> = emptySet(),
        val treeUri: String? = null) : DiscoveryState
    data class Failed(val sessionId: String, val reason: DiscoveryFailure) : DiscoveryState
}

internal fun DiscoveryState.sessionIdOrNull(): String? = when (this) {
    DiscoveryState.Idle -> null
    is DiscoveryState.Choosing -> sessionId
    is DiscoveryState.Scanning -> sessionId
    is DiscoveryState.Preview -> sessionId
    is DiscoveryState.Failed -> sessionId
}

internal fun matchingDiscoveryCandidates(candidates: List<BookCandidate>, query: String): List<BookCandidate> {
    val terms = query.searchKey().trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return if (terms.isEmpty()) candidates else candidates.filter { candidate ->
        val name = candidate.name.searchKey()
        terms.all(name::contains)
    }
}

/** Scan output is session-local: process death never restores source URIs or imports a saved list. */
internal class DiscoveryController(
    private val discovery: BookDiscovery?,
    private val savedState: SavedStateHandle,
    private val scope: CoroutineScope,
) {
    private var pending = restorePending()
    private val _state = MutableStateFlow<DiscoveryState>(pending?.let(DiscoveryState::Choosing) ?: DiscoveryState.Idle)
    val state = _state.asStateFlow()
    val available: Boolean get() = discovery != null
    val busy: Boolean get() = _state.value != DiscoveryState.Idle
    private var scanJob: Pair<String, Job>? = null

    init { if (pending == null) savedState.remove<String>(PENDING_REQUEST_ID) }

    fun prepare(): Boolean {
        if (!available || busy) return false
        val id = UUID.randomUUID().toString()
        pending = id
        savedState[PENDING_REQUEST_ID] = id
        _state.value = DiscoveryState.Choosing(id)
        return true
    }

    fun onTreeResult(treeUri: String?) {
        val id = consumePending() ?: return
        val scanner = discovery
        if (treeUri == null || scanner == null) { _state.value = DiscoveryState.Idle; return }
        _state.value = DiscoveryState.Scanning(id)
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                currentCoroutineContext().ensureActive()
                val result = try {
                    scanner.scan(treeUri) { progress ->
                        _state.update { current ->
                            if (current is DiscoveryState.Scanning && current.sessionId == id && !current.cancelRequested)
                                current.copy(progress = progress) else current
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    DiscoveryResult.Failed(DiscoveryFailure.UNEXPECTED)
                }
                currentCoroutineContext().ensureActive()
                val next = when (result) {
                    is DiscoveryResult.Complete -> preview(id, result.candidates, result.visited, null).withTree(treeUri)
                    is DiscoveryResult.Truncated -> preview(id, result.candidates, result.visited, result.reason).withTree(treeUri)
                    is DiscoveryResult.Failed -> DiscoveryState.Failed(id, result.reason)
                }
                _state.update { current ->
                    if (current is DiscoveryState.Scanning && current.sessionId == id && !current.cancelRequested)
                        next else current
                }
            } finally {
                // A provider may delay cancellation while closing a query. Keep its reservation
                // until that cleanup has actually completed, then discard the interrupted list.
                val current = _state.value
                if (current is DiscoveryState.Scanning && current.sessionId == id) _state.value = DiscoveryState.Idle
                if (scanJob?.first == id) scanJob = null
            }
        }
        if (job.isActive && (_state.value as? DiscoveryState.Scanning)?.sessionId == id) {
            scanJob = id to job
            if ((_state.value as? DiscoveryState.Scanning)?.cancelRequested == true) job.cancel()
        }
    }

    fun onLaunchFailed() {
        val id = consumePending() ?: return
        _state.value = DiscoveryState.Failed(id, DiscoveryFailure.ACCESS)
    }

    fun rejectPending() {
        val id = consumePending() ?: return
        _state.value = DiscoveryState.Failed(id, DiscoveryFailure.UNEXPECTED)
    }

    fun search(sessionId: String, query: String) = updatePreview(sessionId) { it.copy(query = query.take(200)) }

    fun select(sessionId: String, candidateId: String, selected: Boolean) = updatePreview(sessionId) { preview ->
        if (preview.candidates.none { it.id == candidateId }) preview
        else if (selected && candidateId !in preview.selectedIds && preview.selectedIds.size >= MAX_IMPORT_SELECTION) preview
        else preview.copy(selectedIds = if (selected) preview.selectedIds + candidateId else preview.selectedIds - candidateId)
    }

    fun selectAllMatching(sessionId: String) = updatePreview(sessionId) { preview ->
        preview.copy(selectedIds = matchingDiscoveryCandidates(preview.candidates, preview.query)
            .take(MAX_IMPORT_SELECTION).map(BookCandidate::id).toSet())
    }

    fun clearSelection(sessionId: String) = updatePreview(sessionId) { it.copy(selectedIds = emptySet()) }

    fun consumeSelection(sessionId: String): List<String>? {
        val current = _state.value as? DiscoveryState.Preview ?: return null
        if (current.sessionId != sessionId || current.selectedIds.isEmpty() || current.selectedIds.size > MAX_IMPORT_SELECTION) return null
        val selected = current.candidates.filter { it.id in current.selectedIds }
        if (selected.size != current.selectedIds.size) return null
        val uris = selected.map(BookCandidate::documentUri)
        _state.value = DiscoveryState.Idle
        return uris
    }

    fun dismiss(sessionId: String) {
        if (_state.value.sessionIdOrNull() != sessionId) return
        when (_state.value) {
            is DiscoveryState.Scanning -> cancel(sessionId)
            is DiscoveryState.Choosing -> {
                consumePending()
                _state.value = DiscoveryState.Idle
            }
            else -> _state.value = DiscoveryState.Idle
        }
    }

    fun cancel(sessionId: String) {
        val current = _state.value as? DiscoveryState.Scanning ?: return
        if (current.sessionId != sessionId || current.cancelRequested) return
        _state.value = current.copy(cancelRequested = true)
        scanJob?.takeIf { it.first == sessionId }?.second?.cancel()
    }

    private inline fun updatePreview(sessionId: String, update: (DiscoveryState.Preview) -> DiscoveryState.Preview) {
        val current = _state.value as? DiscoveryState.Preview ?: return
        if (current.sessionId == sessionId) _state.value = update(current)
    }

    private fun preview(id: String, candidates: List<BookCandidate>, visited: Int, limit: DiscoveryLimit?): DiscoveryState {
        val snapshot = candidates.toList()
        if (visited < 0 || snapshot.size > 1000 || snapshot.any { it.id.isBlank() || it.name.isBlank() || it.documentUri.isBlank() } ||
            snapshot.map(BookCandidate::id).toSet().size != snapshot.size ||
            snapshot.map(BookCandidate::documentUri).toSet().size != snapshot.size)
            return DiscoveryState.Failed(id, DiscoveryFailure.UNEXPECTED)
        return DiscoveryState.Preview(id, snapshot, visited, limit)
    }

    private fun consumePending(): String? {
        val id = pending ?: return null
        if (restorePending() != id) { pending = null; return null }
        pending = null
        savedState.remove<String>(PENDING_REQUEST_ID)
        return id
    }

    private fun DiscoveryState.withTree(uri: String): DiscoveryState =
        if (this is DiscoveryState.Preview) copy(treeUri = uri) else this

    private fun restorePending(): String? {
        if (discovery == null) return null
        val id = savedState.get<Any>(PENDING_REQUEST_ID) as? String ?: return null
        return id.takeIf { runCatching { UUID.fromString(it).toString() }.getOrNull() == it }
    }

    private companion object { const val PENDING_REQUEST_ID = "pendingDiscoveryRequestId" }
}
