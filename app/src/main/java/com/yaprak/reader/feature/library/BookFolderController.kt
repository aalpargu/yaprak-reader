package com.yaprak.reader.feature.library

import com.yaprak.reader.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BookFolderFeedback(val sessionId: String?, val kind: Kind) {
    enum class Kind { SAVED, FORGOTTEN, AUTO_ENABLED, AUTO_DISABLED, CLEANUP, FAILED, CHANGED, ACCESS }
}

data class BookFolderState(
    val available: Boolean = false,
    val loading: Boolean = false,
    val snapshot: BookFolderSnapshot? = null,
    val failure: BookFolderFailure? = null,
    val saving: Boolean = false,
    val resolving: Boolean = false,
    internal val feedback: BookFolderFeedback? = null,
) {
    val busy: Boolean get() = saving || resolving
    val ready: Boolean get() = available && !loading && failure == null && snapshot != null
}

/** Device preference observation and explicit saved-folder actions. No Android, file or SDK work. */
internal class BookFolderController(
    private val repository: BookFolderRepository?,
    private val scope: CoroutineScope,
    private val mayMutate: () -> Boolean,
    private val preview: () -> DiscoveryState.Preview?,
    private val beginScan: (String) -> Boolean,
    private val currentScanId: () -> String? = { null },
    private val cancelOpeningScan: (String) -> Unit = {},
) {
    private val mutable = MutableStateFlow(BookFolderState(available = repository != null))
    val state = mutable.asStateFlow()
    private var sourceJob: Job? = null
    private var sourceGeneration = 0L
    private var scanJob: Job? = null
    private var actionGeneration = 0L
    private var owner: String? = null
    private var foreground = false
    private data class OpeningUi(val loaded: Boolean, val eligible: Boolean)
    private var openingUi: OpeningUi? = null
    // One decision per library ViewModel lifetime. Rotation/resume/recomposition and saving
    // the opt-in cannot reopen a dismissed preview or turn an existing screen into a scan.
    private var openingConsidered = false
    private var startedOpeningScan: String? = null

    init { reload() }

    fun bind(owner: String, resumed: Boolean) {
        if (this.owner != owner) openingUi = null
        if (this.owner != owner || !resumed) cancelResolve()
        this.owner = owner; foreground = resumed
        considerOpeningScan()
    }
    fun release(owner: String) {
        if (this.owner != owner) return
        foreground = false; this.owner = null; openingUi = null; cancelResolve()
    }
    fun reportOpeningUi(actionOwner: String, loaded: Boolean, eligible: Boolean) {
        if (actionOwner != owner) return
        openingUi = OpeningUi(loaded, eligible)
        considerOpeningScan()
    }
    private fun considerOpeningScan() {
        val ui = openingUi ?: return
        if (openingConsidered || !foreground || !ui.loaded || state.value.loading) return
        if (!state.value.ready && state.value.failure == null) return
        openingConsidered = true
        val snapshot = state.value.snapshot ?: return
        val capturedOwner = owner ?: return
        if (ui.eligible && snapshot.automaticScan && !state.value.busy && mayMutate() && preview() == null)
            startScan(capturedOwner, snapshot, opening = true)
    }
    private fun cancelResolve() {
        startedOpeningScan?.let(cancelOpeningScan); startedOpeningScan = null
        actionGeneration++; scanJob?.cancel(); scanJob = null
        mutable.update { it.copy(resolving = false) }
    }
    fun cancelScan(actionOwner: String) { if (actionOwner == owner && state.value.resolving) cancelResolve() }

    fun reload() {
        val repo = repository ?: return
        if (state.value.busy) return
        val generation = ++sourceGeneration; sourceJob?.cancel()
        mutable.update { it.copy(loading = true, snapshot = null, failure = null) }
        sourceJob = scope.launch {
            try {
                repo.observe().collect { snapshot ->
                    currentCoroutineContext().ensureActive()
                    if (!snapshot.valid) throw BookFolderException(BookFolderFailure.SOURCE)
                    if (generation == sourceGeneration) {
                        mutable.update { it.copy(loading = false, snapshot = snapshot, failure = null) }
                        considerOpeningScan()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == sourceGeneration) {
                    mutable.update { it.copy(loading = false, snapshot = null,
                        failure = (failure as? BookFolderException)?.reason ?: BookFolderFailure.SOURCE) }
                    considerOpeningScan()
                }
            }
        }
    }

    fun remember(actionOwner: String, sessionId: String, expectedTreeUri: String, expected: BookFolderSnapshot) {
        if (actionOwner != owner) return
        val current = preview()?.takeIf { it.sessionId == sessionId } ?: return
        val uri = current.treeUri?.takeIf { it == expectedTreeUri } ?: return
        write(expected, sessionId) { repo, captured -> repo.save(captured, uri) }
    }
    fun forget(actionOwner: String, expected: BookFolderSnapshot) {
        if (actionOwner != owner) return
        if (preview() != null || expected.folder == null) return
        write(expected, null) { repo, captured -> repo.forget(captured) }
    }
    fun setAutomaticScan(actionOwner: String, expected: BookFolderSnapshot, enabled: Boolean) {
        if (actionOwner != owner || preview() != null || expected.folder == null) return
        write(expected, null, if (enabled) BookFolderFeedback.Kind.AUTO_ENABLED else BookFolderFeedback.Kind.AUTO_DISABLED) {
            repo, captured -> repo.setAutomaticScan(captured, enabled)
        }
    }
    private fun write(expected: BookFolderSnapshot, sessionId: String?, success: BookFolderFeedback.Kind? = null,
        mutation: suspend (BookFolderRepository, BookFolderSnapshot) -> BookFolderWrite) {
        val repo = repository ?: return
        if (!foreground || !state.value.ready || state.value.busy || state.value.snapshot != expected || !mayMutate()) return
        openingConsidered = true
        sourceGeneration++; sourceJob?.cancel(); sourceJob = null
        mutable.update { it.copy(saving = true, feedback = null) }
        scope.launch {
            try {
                val result = mutation(repo, expected)
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(snapshot = result.snapshot, failure = null, feedback = BookFolderFeedback(sessionId,
                    if (result.cleanupPending) BookFolderFeedback.Kind.CLEANUP else if (success != null) success else if (result.snapshot.folder == null)
                        BookFolderFeedback.Kind.FORGOTTEN else BookFolderFeedback.Kind.SAVED)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val kind = when ((failure as? BookFolderException)?.reason) {
                    BookFolderFailure.CHANGED -> BookFolderFeedback.Kind.CHANGED
                    BookFolderFailure.ACCESS -> BookFolderFeedback.Kind.ACCESS
                    else -> BookFolderFeedback.Kind.FAILED
                }
                mutable.update { it.copy(feedback = BookFolderFeedback(sessionId, kind)) }
            } finally {
                mutable.update { it.copy(saving = false) }
                reload()
            }
        }
    }

    fun scan(actionOwner: String, expected: BookFolderSnapshot) {
        startScan(actionOwner, expected, opening = false)
    }
    private fun startScan(actionOwner: String, expected: BookFolderSnapshot, opening: Boolean) {
        if (actionOwner != owner) return
        val repo = repository ?: return
        if (!foreground || !state.value.ready || state.value.busy || state.value.snapshot != expected ||
            expected.folder == null || preview() != null || !mayMutate()) return
        val capturedOwner = owner ?: return; val generation = ++actionGeneration
        openingConsidered = true
        mutable.update { it.copy(resolving = true, feedback = null) }
        scanJob = scope.launch {
            try {
                val uri = repo.resolveForScan(expected)
                currentCoroutineContext().ensureActive()
                if (generation != actionGeneration || !foreground || owner != capturedOwner || !mayMutate() ||
                    state.value.snapshot != expected || preview() != null) return@launch
                if (!beginScan(uri)) throw BookFolderException(BookFolderFailure.CHANGED)
                if (opening) startedOpeningScan = currentScanId()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == actionGeneration) mutable.update { it.copy(feedback = BookFolderFeedback(null,
                    if ((failure as? BookFolderException)?.reason == BookFolderFailure.ACCESS) BookFolderFeedback.Kind.ACCESS
                    else if ((failure as? BookFolderException)?.reason == BookFolderFailure.CHANGED) BookFolderFeedback.Kind.CHANGED
                    else BookFolderFeedback.Kind.FAILED)) }
            } finally {
                if (generation == actionGeneration) { scanJob = null; mutable.update { it.copy(resolving = false) } }
            }
        }
    }
}
