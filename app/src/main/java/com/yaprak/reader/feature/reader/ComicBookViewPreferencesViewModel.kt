package com.yaprak.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

internal data class ComicBookViewOwner(val bookId: String, val contentSha256: String, val generation: Long)
internal enum class ComicBookViewStatus { INACTIVE, LOADING, APPLYING, READY, SAVING, LOAD_FAILED, SAVE_FAILED, APPLY_FAILED }
internal data class ComicBookViewPreferencesState(
    val owner: ComicBookViewOwner? = null,
    val preferences: BookComicViewPreferences? = null,
    val applied: BookComicViewPreferences? = null,
    val status: ComicBookViewStatus = ComicBookViewStatus.INACTIVE,
    val failure: BookComicViewPreferencesFailure? = null,
    val revision: Long = 0,
) {
    val canEdit get() = preferences != null && status in setOf(ComicBookViewStatus.READY, ComicBookViewStatus.SAVE_FAILED)
    val failed get() = status in setOf(ComicBookViewStatus.LOAD_FAILED, ComicBookViewStatus.SAVE_FAILED, ComicBookViewStatus.APPLY_FAILED)
}

/** Durable values belong to one verified book/hash and foreground reader generation. */
internal class ComicBookViewPreferencesViewModel(private val repository: BookComicViewPreferencesRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ComicBookViewPreferencesState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var observing: Job? = null
    private var writing: Job? = null
    private var pending: Boolean? = null
    fun bind(book: Book?) {
        if (book?.format?.comic != true) { deactivate(); return }
        val old = state.value.owner
        if (old?.bookId == book.id && old.contentSha256 == book.sha256 && state.value.status != ComicBookViewStatus.INACTIVE) return
        deactivate()
        val owner = ComicBookViewOwner(book.id, book.sha256, ++generation)
        mutable.value = ComicBookViewPreferencesState(owner, status = ComicBookViewStatus.LOADING)
        observe(owner)
    }
    fun deactivate() {
        generation++
        val oldObservation = observing; val oldWrite = writing
        observing = null; writing = null; pending = null
        mutable.value = ComicBookViewPreferencesState()
        oldObservation?.cancel(); oldWrite?.cancel()
    }
    override fun onCleared() { deactivate(); super.onCleared() }
    fun isCurrent(expected: ComicBookViewPreferencesState) = expected.owner != null && state.value.owner == expected.owner &&
        state.value.revision == expected.revision && state.value.preferences == expected.preferences
    fun applied(expected: ComicBookViewPreferencesState) {
        if (isCurrent(expected) && state.value.status == ComicBookViewStatus.APPLYING)
            mutable.value = state.value.copy(applied = expected.preferences, status = ComicBookViewStatus.READY)
    }
    fun applyFailed(expected: ComicBookViewPreferencesState) {
        if (isCurrent(expected) && state.value.status == ComicBookViewStatus.APPLYING)
            mutable.value = state.value.copy(status = ComicBookViewStatus.APPLY_FAILED)
    }
    suspend fun retry() {
        val current = state.value
        when (current.status) {
            ComicBookViewStatus.LOAD_FAILED -> current.owner?.let { owner ->
                mutable.value = current.copy(status = ComicBookViewStatus.LOADING, failure = null); observe(owner)
            }
            ComicBookViewStatus.SAVE_FAILED -> pending?.let { saveSpread(current, it) }
            ComicBookViewStatus.APPLY_FAILED -> mutable.value = current.copy(status = ComicBookViewStatus.APPLYING, revision = current.revision + 1)
            else -> Unit
        }
    }
    private fun observe(owner: ComicBookViewOwner) {
        observing?.cancel()
        observing = viewModelScope.launch {
            try {
                repository.observe(owner.bookId, owner.contentSha256).collect { value ->
                    currentCoroutineContext().ensureActive()
                    if (state.value.owner != owner) return@collect
                    val current = state.value
                    if (current.preferences != value || current.status == ComicBookViewStatus.LOADING) {
                        pending = null
                        mutable.value = current.copy(preferences = value, status = ComicBookViewStatus.APPLYING,
                            failure = null, revision = current.revision + 1)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (state.value.owner == owner) {
                    pending = null
                    mutable.value = state.value.copy(status = ComicBookViewStatus.LOAD_FAILED,
                        failure = failure.reason(), revision = state.value.revision + 1)
                }
            }
        }
    }
    suspend fun saveSpread(expected: ComicBookViewPreferencesState, enabled: Boolean): ComicBookViewPreferencesState? {
        if (state.value != expected || !expected.canEdit || writing?.isActive == true) return null
        val owner = checkNotNull(expected.owner); val job = currentCoroutineContext()[Job]
        writing = job; pending = enabled; observing?.cancel(); observing = null
        mutable.value = state.value.copy(status = ComicBookViewStatus.SAVING, failure = null, revision = state.value.revision + 1)
        try {
            repository.saveSpread(owner.bookId, owner.contentSha256, enabled)
            currentCoroutineContext().ensureActive()
            if (state.value.owner != owner) return null
            val committed = repository.observe(owner.bookId, owner.contentSha256).first()
            currentCoroutineContext().ensureActive()
            if (state.value.owner != owner) return null
            pending = null
            val accepted = state.value.copy(preferences = committed, status = ComicBookViewStatus.APPLYING,
                failure = null, revision = state.value.revision + 1)
            mutable.value = accepted; observe(owner); return accepted
        } catch (cancelled: CancellationException) {
            if (state.value.owner == owner) {
                mutable.value = state.value.copy(status = ComicBookViewStatus.LOADING, revision = state.value.revision + 1)
                pending = null; observe(owner)
            }
            throw cancelled
        } catch (failure: Exception) {
            if (state.value.owner == owner) {
                mutable.value = state.value.copy(status = ComicBookViewStatus.SAVE_FAILED, failure = failure.reason()); observe(owner)
            }
            return null
        } finally { if (writing === job) writing = null }
    }
    private fun Exception.reason() = (this as? BookComicViewPreferencesException)?.reason ?: BookComicViewPreferencesFailure.SOURCE
}
