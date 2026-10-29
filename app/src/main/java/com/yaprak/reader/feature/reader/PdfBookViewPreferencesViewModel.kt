package com.yaprak.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.settings.BookPdfViewPreferences
import com.yaprak.reader.domain.settings.BookPdfViewPreferencesException
import com.yaprak.reader.domain.settings.BookPdfViewPreferencesFailure
import com.yaprak.reader.domain.settings.BookPdfViewPreferencesRepository
import com.yaprak.reader.domain.settings.PdfFitPreference
import com.yaprak.reader.domain.settings.PdfNavigationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal data class PdfBookViewOwner(val bookId: String, val contentSha256: String, val generation: Long)

internal enum class PdfBookViewStatus { INACTIVE, LOADING, APPLYING, READY, SAVING, LOAD_FAILED, SAVE_FAILED, APPLY_FAILED }

internal data class PdfBookViewPreferencesState(
    val owner: PdfBookViewOwner? = null,
    val preferences: BookPdfViewPreferences? = null,
    val applied: BookPdfViewPreferences? = null,
    val status: PdfBookViewStatus = PdfBookViewStatus.INACTIVE,
    val failure: BookPdfViewPreferencesFailure? = null,
    val revision: Long = 0,
    val fitCommand: Long = 0,
    val cropCommand: Long = 0,
) {
    val canEdit: Boolean get() = preferences != null && status in setOf(
        PdfBookViewStatus.READY, PdfBookViewStatus.SAVE_FAILED)
    val failed: Boolean get() = status in setOf(PdfBookViewStatus.LOAD_FAILED,
        PdfBookViewStatus.SAVE_FAILED, PdfBookViewStatus.APPLY_FAILED)
}

/** Durable values and operations belong to one verified book/hash and foreground reader lease. */
internal class PdfBookViewPreferencesViewModel(
    private val repository: BookPdfViewPreferencesRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(PdfBookViewPreferencesState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var observing: Job? = null
    private var writing: Job? = null
    private var pending: Change? = null
    private var fitCommand = 0L
    private var cropCommand = 0L

    fun bind(bookId: String, contentSha256: String) {
        val old = state.value.owner
        if (old?.bookId == bookId && old.contentSha256 == contentSha256 &&
            state.value.status != PdfBookViewStatus.INACTIVE) return
        deactivate()
        val owner = PdfBookViewOwner(bookId, contentSha256, ++generation)
        mutable.value = PdfBookViewPreferencesState(owner, status = PdfBookViewStatus.LOADING,
            fitCommand = fitCommand, cropCommand = cropCommand)
        observe(owner)
    }

    fun deactivate() {
        generation++
        val oldObservation = observing
        val oldWrite = writing
        observing = null
        writing = null
        pending = null
        mutable.value = PdfBookViewPreferencesState(status = PdfBookViewStatus.INACTIVE)
        // Invalidate before cancelling: an undispatched cancellation handler must not reopen the old source.
        oldObservation?.cancel()
        oldWrite?.cancel()
    }

    override fun onCleared() { deactivate(); super.onCleared() }

    fun isCurrent(expected: PdfBookViewPreferencesState): Boolean = expected.owner != null &&
        state.value.owner == expected.owner && state.value.revision == expected.revision &&
        state.value.preferences == expected.preferences

    fun applied(expected: PdfBookViewPreferencesState) {
        if (isCurrent(expected) && state.value.status == PdfBookViewStatus.APPLYING)
            mutable.value = state.value.copy(applied = expected.preferences, status = PdfBookViewStatus.READY)
    }

    fun applyFailed(expected: PdfBookViewPreferencesState) {
        if (isCurrent(expected) && state.value.status == PdfBookViewStatus.APPLYING)
            mutable.value = state.value.copy(status = PdfBookViewStatus.APPLY_FAILED)
    }

    fun retryApply() {
        if (state.value.status == PdfBookViewStatus.APPLY_FAILED)
            mutable.value = state.value.copy(status = PdfBookViewStatus.APPLYING, revision = state.value.revision + 1)
    }

    suspend fun saveCrop(expected: PdfBookViewPreferencesState, horizontal: Int, vertical: Int) =
        save(expected, Change.Crop(horizontal, vertical))

    suspend fun saveFit(expected: PdfBookViewPreferencesState, mode: PdfFitPreference) =
        save(expected, Change.Fit(mode))

    suspend fun saveSpread(expected: PdfBookViewPreferencesState, enabled: Boolean) =
        save(expected, Change.Spread(enabled))

    suspend fun saveNavigation(expected: PdfBookViewPreferencesState, mode: PdfNavigationMode) =
        save(expected, Change.Navigation(mode))

    suspend fun retry() {
        val current = state.value
        when (current.status) {
            PdfBookViewStatus.LOAD_FAILED -> {
                val owner = current.owner ?: return
                mutable.value = current.copy(status = PdfBookViewStatus.LOADING, failure = null)
                observe(owner)
            }
            PdfBookViewStatus.SAVE_FAILED -> pending?.let { save(current, it) }
            PdfBookViewStatus.APPLY_FAILED -> retryApply()
            else -> Unit
        }
    }

    private fun observe(owner: PdfBookViewOwner) {
        observing?.cancel()
        observing = viewModelScope.launch {
            try {
                repository.observe(owner.bookId, owner.contentSha256).collect { value ->
                    currentCoroutineContext().ensureActive()
                    if (state.value.owner != owner) return@collect
                    val current = state.value
                    if (current.preferences != value || current.status == PdfBookViewStatus.LOADING) {
                        pending = null
                        mutable.value = current.copy(preferences = value, status = PdfBookViewStatus.APPLYING,
                            failure = null, revision = current.revision + 1)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (state.value.owner == owner) {
                    pending = null
                    mutable.value = state.value.copy(status = PdfBookViewStatus.LOAD_FAILED,
                        failure = failure.reason(), revision = state.value.revision + 1)
                }
            }
        }
    }

    private suspend fun save(expected: PdfBookViewPreferencesState, change: Change): PdfBookViewPreferencesState? {
        if (state.value != expected || !state.value.canEdit || writing?.isActive == true) return null
        val owner = checkNotNull(expected.owner)
        val job = currentCoroutineContext()[Job]
        writing = job
        pending = change
        observing?.cancel(); observing = null
        mutable.value = state.value.copy(status = PdfBookViewStatus.SAVING, failure = null,
            revision = state.value.revision + 1)
        try {
            when (change) {
                is Change.Crop -> repository.saveCrop(owner.bookId, owner.contentSha256, change.horizontal, change.vertical)
                is Change.Fit -> repository.saveFit(owner.bookId, owner.contentSha256, change.mode)
                is Change.Spread -> repository.saveSpread(owner.bookId, owner.contentSha256, change.enabled)
                is Change.Navigation -> repository.saveNavigation(owner.bookId, owner.contentSha256, change.mode)
            }
            currentCoroutineContext().ensureActive()
            if (state.value.owner != owner) return null
            // A field write must retain any newer durable fields. Requery the whole verified row.
            val committed = repository.observe(owner.bookId, owner.contentSha256).first()
            currentCoroutineContext().ensureActive()
            if (state.value.owner != owner) return null
            pending = null
            if (change is Change.Fit) fitCommand++
            if (change is Change.Crop) cropCommand++
            val accepted = state.value.copy(preferences = committed, status = PdfBookViewStatus.APPLYING,
                failure = null, revision = state.value.revision + 1,
                fitCommand = fitCommand, cropCommand = cropCommand)
            mutable.value = accepted
            observe(owner)
            return accepted
        } catch (cancelled: CancellationException) {
            if (state.value.owner == owner) {
                // Cancellation may follow a commit; next active reader rechecks durable values.
                mutable.value = state.value.copy(status = PdfBookViewStatus.LOADING, revision = state.value.revision + 1)
                pending = null
                observe(owner)
            }
            throw cancelled
        } catch (failure: Exception) {
            if (state.value.owner == owner) {
                mutable.value = state.value.copy(status = PdfBookViewStatus.SAVE_FAILED, failure = failure.reason())
                // Still observe the verified row. An external edit/source change invalidates pending actions.
                observe(owner)
            }
            return null
        } finally {
            if (writing === job) writing = null
        }
    }

    private fun Exception.reason() = (this as? BookPdfViewPreferencesException)?.reason
        ?: BookPdfViewPreferencesFailure.SOURCE

    private sealed interface Change {
        data class Crop(val horizontal: Int, val vertical: Int) : Change
        data class Fit(val mode: PdfFitPreference) : Change
        data class Spread(val enabled: Boolean) : Change
        data class Navigation(val mode: PdfNavigationMode) : Change
    }
}
