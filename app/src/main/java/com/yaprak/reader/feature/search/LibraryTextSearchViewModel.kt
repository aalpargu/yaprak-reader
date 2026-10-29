package com.yaprak.reader.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.LibraryTextBookState
import com.yaprak.reader.domain.search.BookTextScope
import com.yaprak.reader.domain.search.LibraryTextHit
import com.yaprak.reader.domain.search.LibraryTextIndex
import com.yaprak.reader.domain.search.LibraryTextIndexException
import com.yaprak.reader.domain.search.LibraryTextIndexFailure
import com.yaprak.reader.domain.search.LibraryTextIndexProgress
import com.yaprak.reader.domain.search.LibraryTextSearchResult
import com.yaprak.reader.domain.search.limitLibraryTextQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface LibraryTextBuildPhase {
    data object Idle : LibraryTextBuildPhase
    data class Running(val id: Long, val progress: LibraryTextIndexProgress? = null,
        val stopping: Boolean = false) : LibraryTextBuildPhase
    data class Finished(val id: Long, val cancelled: Boolean, val bookState: LibraryTextBookState? = null) : LibraryTextBuildPhase
    data class Failed(val id: Long, val reason: LibraryTextIndexFailure) : LibraryTextBuildPhase
}

internal enum class LibraryTextActionFeedback { CHANGED, OPEN_FAILED }
internal data class LibraryTextHitFeedback(val hit: LibraryTextHit, val kind: LibraryTextActionFeedback)

internal data class LibraryTextSearchState(
    val query: String = "",
    val searchedQuery: String? = null,
    val result: LibraryTextSearchResult? = null,
    val loading: Boolean = true,
    val failure: LibraryTextIndexFailure? = null,
    val active: Boolean = false,
    val revision: Long = 0,
    val build: LibraryTextBuildPhase = LibraryTextBuildPhase.Idle,
    val action: LibraryTextHit? = null,
    val feedback: LibraryTextHitFeedback? = null,
    val scope: BookTextScope? = null,
) {
    val building: Boolean get() = build is LibraryTextBuildPhase.Running
    val actionsEnabled: Boolean get() = active && !loading && failure == null && !building && action == null &&
        searchedQuery != null && result?.query == searchedQuery && result?.scope == scope
    val canRebuild: Boolean get() = active && !building && action == null
    val canSearch: Boolean get() = active && !building && action == null && query.isNotBlank()
    val canChangeScope: Boolean get() = active && !building && action == null
}

/** Reads an existing local index. Only the explicit rebuild action extracts source books. */
internal class LibraryTextSearchViewModel(
    private val index: LibraryTextIndex,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val restoredQuery = limitLibraryTextQuery(savedState.get<Any?>(QUERY) as? String ?: "")
    private val restoredScope = ((savedState.get<Any?>(SCOPE_BOOK) as? String)?.let { id ->
        (savedState.get<Any?>(SCOPE_HASH) as? String)?.let { hash -> BookTextScope(id, hash) }
    })?.takeIf { it.isValid }
    private val mutable = MutableStateFlow(LibraryTextSearchState(query = restoredQuery, scope = restoredScope))
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var actionJob: Job? = null
    private var buildJob: Job? = null
    private var generation = 0L
    private var actionSerial = 0L
    private var buildSerial = 0L
    private var lifecycleOwner: Any? = null

    init { savedState[QUERY] = restoredQuery; saveScope(restoredScope) }

    fun changeScope(scope: BookTextScope?, revision: Long = state.value.revision) {
        val current = state.value
        if (!current.canChangeScope || current.revision != revision || current.scope == scope) return
        if (scope != null && (!scope.isValid || current.result?.books?.singleOrNull {
            it.book.id == scope.bookId && it.book.sha256 == scope.contentSha256 &&
                (it.book.format == BookFormat.EPUB || it.state != com.yaprak.reader.domain.search.LibraryTextBookState.UNSUPPORTED)
        } == null)) return
        saveScope(scope)
        mutable.update { it.copy(scope = scope, searchedQuery = null) }
        observe()
    }

    private fun saveScope(scope: BookTextScope?) {
        if (scope == null) { savedState.remove<String>(SCOPE_BOOK); savedState.remove<String>(SCOPE_HASH) }
        else { savedState[SCOPE_BOOK] = scope.bookId; savedState[SCOPE_HASH] = scope.contentSha256 }
    }

    fun changeQuery(value: String) {
        val query = limitLibraryTextQuery(value)
        if (!state.value.active || query == state.value.query) return
        savedState[QUERY] = query
        mutable.update { it.copy(query = query, searchedQuery = null) }
        observe()
    }

    fun submit(query: String = state.value.query, revision: Long = state.value.revision) {
        val current = state.value
        if (!current.canSearch || current.query != query || current.revision != revision) return
        mutable.update { it.copy(searchedQuery = query) }
        observe()
    }

    fun retry(revision: Long = state.value.revision) {
        if (!state.value.active || state.value.building || state.value.revision != revision) return
        observe()
    }

    /** Old Activity pause/dispose calls never cancel its replacement owner's work. */
    fun attachLifecycleOwner(owner: Any) {
        if (lifecycleOwner === owner) return
        lifecycleOwner = owner
        setActive(false)
    }
    fun setLifecycleOwnerActive(owner: Any, active: Boolean) {
        if (lifecycleOwner === owner) setActive(active)
    }
    fun isLifecycleOwnerActive(owner: Any): Boolean = lifecycleOwner === owner && state.value.active
    fun detachLifecycleOwner(owner: Any) {
        if (lifecycleOwner !== owner) return
        lifecycleOwner = null
        setActive(false)
    }
    fun setActive(active: Boolean) {
        if (state.value.active == active) return
        mutable.update { it.copy(active = active) }
        if (!active) cancelBuild()
        observe()
    }

    fun rebuild(expected: LibraryTextBuildPhase = state.value.build, expectedScope: BookTextScope? = state.value.scope,
        revision: Long = state.value.revision) {
        val current = state.value
        if (!current.canRebuild || current.build != expected || current.scope != expectedScope ||
            current.revision != revision || buildJob != null) return
        val selected = current.scope
        val id = ++buildSerial
        mutable.update { it.copy(build = LibraryTextBuildPhase.Running(id), searchedQuery = null) }
        observe()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val onProgress: (LibraryTextIndexProgress) -> Unit = { progress ->
                    mutable.update { current ->
                        val running = current.build as? LibraryTextBuildPhase.Running
                        if (running != null && running.id == id && !running.stopping && buildJob?.isActive == true)
                            current.copy(build = running.copy(progress = progress)) else current
                    }
                }
                val bookState = if (selected == null) { index.rebuild(onProgress); null }
                    else index.rebuildBook(selected, onProgress).also {
                        if (it == LibraryTextBookState.NOT_INDEXED) throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
                    }
                currentCoroutineContext().ensureActive()
                if ((state.value.build as? LibraryTextBuildPhase.Running)?.id == id)
                    mutable.update { it.copy(build = LibraryTextBuildPhase.Finished(id, cancelled = false, bookState = bookState)) }
            } catch (cancelled: CancellationException) {
                if ((state.value.build as? LibraryTextBuildPhase.Running)?.id == id)
                    mutable.update { it.copy(build = LibraryTextBuildPhase.Finished(id, cancelled = true)) }
                throw cancelled
            } catch (failure: Exception) {
                if ((state.value.build as? LibraryTextBuildPhase.Running)?.id == id)
                    mutable.update { it.copy(build = LibraryTextBuildPhase.Failed(id, classify(failure))) }
            }
        }
        buildJob = job
        job.invokeOnCompletion {
            // A lazy job cancelled before its first dispatch never enters the try/finally body.
            // Completion also waits for the source's cancellation cleanup before enabling rebuild.
            if (buildSerial == id) {
                if ((state.value.build as? LibraryTextBuildPhase.Running)?.id == id)
                    mutable.update { it.copy(build = LibraryTextBuildPhase.Finished(id, cancelled = job.isCancelled)) }
                buildJob = null
                observe()
            }
        }
        job.start()
    }

    fun cancelBuild(id: Long? = (state.value.build as? LibraryTextBuildPhase.Running)?.id) {
        val running = state.value.build as? LibraryTextBuildPhase.Running ?: return
        if (running.id != id || running.stopping) return
        mutable.update { it.copy(build = running.copy(stopping = true)) }
        buildJob?.cancel()
    }

    fun open(hit: LibraryTextHit, openChapter: (LibraryTextHit) -> Unit) = open(hit, state.value.revision, openChapter)

    fun open(hit: LibraryTextHit, revision: Long, openChapter: (LibraryTextHit) -> Unit) {
        val current = state.value
        if (!current.actionsEnabled || current.revision != revision || current.result?.hits?.singleOrNull { sameIdentity(it, hit) } != hit) return
        val request = generation
        val serial = ++actionSerial
        mutable.update { it.copy(action = hit, feedback = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val resolved = index.resolve(hit)
                if (!actionCurrent(hit, request, serial)) return@launch
                if (resolved == null || resolved != hit || !resolved.target.isValid) {
                    mutable.update { it.copy(feedback = LibraryTextHitFeedback(hit, LibraryTextActionFeedback.CHANGED)) }
                    return@launch
                }
                // No suspension between exact-list/lifecycle validation and the user's UI effect.
                openChapter(resolved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (actionCurrent(hit, request, serial))
                    mutable.update { it.copy(feedback = LibraryTextHitFeedback(hit, LibraryTextActionFeedback.OPEN_FAILED)) }
            } finally {
                if (serial == actionSerial) {
                    actionJob = null
                    mutable.update { it.copy(action = null) }
                }
            }
        }
        actionJob = job
        job.start()
    }

    private fun actionCurrent(hit: LibraryTextHit, request: Long, serial: Long): Boolean {
        val current = state.value
        return request == generation && serial == actionSerial && current.active && !current.loading &&
            current.failure == null && !current.building && current.action == hit &&
            current.result?.query == current.searchedQuery &&
            current.result?.hits?.singleOrNull { sameIdentity(it, hit) } == hit
    }

    private fun observe() {
        val request = ++generation
        cancelAction()
        observation?.cancel()
        observation = null
        val query = state.value.searchedQuery ?: ""
        val scope = state.value.scope
        mutable.update { it.copy(result = null, loading = true, failure = null, feedback = null, revision = request) }
        if (!state.value.active) return
        observation = viewModelScope.launch {
            try {
                val source = if (scope == null) index.observe(query) else index.observeBook(query, scope)
                source.collect { result ->
                    if (request != generation || (state.value.searchedQuery ?: "") != query ||
                        state.value.scope != scope || !state.value.active) return@collect
                    check(result.query == query && result.scope == scope)
                    if (scope != null) check(result.hits.all {
                        it.book.id == scope.bookId && it.book.sha256 == scope.contentSha256 &&
                            it.target.bookId == scope.bookId && it.target.contentSha256 == scope.contentSha256
                    })
                    val snapshot = result.copy(hits = result.hits.toList(), books = result.books.toList())
                    state.value.action?.let { action ->
                        if (snapshot.hits.singleOrNull { sameIdentity(it, action) } != action) cancelAction()
                    }
                    mutable.update { current -> current.copy(result = snapshot, loading = false, failure = null,
                        feedback = current.feedback?.takeIf { it.hit in snapshot.hits }) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (request == generation && state.value.active) {
                    cancelAction()
                    mutable.update { it.copy(result = null, loading = false, failure = classify(failure), feedback = null) }
                }
            }
        }
    }

    private fun cancelAction() {
        ++actionSerial
        actionJob?.cancel()
        actionJob = null
        mutable.update { it.copy(action = null, feedback = null) }
    }

    private fun sameIdentity(a: LibraryTextHit, b: LibraryTextHit) = a.id == b.id && a.generation == b.generation &&
        a.book.id == b.book.id && a.target == b.target
    private fun classify(failure: Exception) = (failure as? LibraryTextIndexException)?.reason ?: LibraryTextIndexFailure.SOURCE
    companion object {
        internal const val QUERY = "libraryTextSearchQuery"
        internal const val SCOPE_BOOK = "libraryTextSearchScopeBook"
        internal const val SCOPE_HASH = "libraryTextSearchScopeHash"
    }
}
