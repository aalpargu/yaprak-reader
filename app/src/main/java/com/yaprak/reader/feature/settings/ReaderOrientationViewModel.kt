package com.yaprak.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.settings.ReaderOrientation
import com.yaprak.reader.domain.settings.ReaderOrientationSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderOrientationState(
    val saved: ReaderOrientation = ReaderOrientation.AUTO,
    val draft: ReaderOrientation = ReaderOrientation.AUTO,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val saveFailed: Boolean = false,
    val saving: Boolean = false,
    val open: Boolean = false,
    val revision: Long = 0,
)

/** Editing only: no Activity or platform orientation constants cross this boundary. */
class ReaderOrientationViewModel(private val repository: ReaderOrientationSettingsRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ReaderOrientationState())
    val state = mutable.asStateFlow()
    private var loadingJob: Job? = null
    private var generation = 0L

    init { reload() }

    fun reload() {
        if (state.value.saving) return
        val request = ++generation
        loadingJob?.cancel()
        mutable.update { it.copy(loading = true, loadFailed = false, revision = it.revision + 1) }
        loadingJob = viewModelScope.launch {
            var received = false
            try {
                repository.orientation.collect { saved ->
                    currentCoroutineContext().ensureActive()
                    if (request != generation) return@collect
                    received = true
                    // A DataStore emission may precede the save method returning. Publish the
                    // requested value only after that durable save reports success.
                    if (!state.value.saving) mutable.update {
                        it.copy(saved = saved, draft = if (it.open && !it.loading) it.draft else saved,
                            loading = false, loadFailed = false, revision = it.revision + 1)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!received && request == generation) failLoad()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (request == generation) failLoad()
            }
        }
    }

    private fun failLoad() {
        mutable.update { it.copy(loading = false, loadFailed = true, revision = it.revision + 1) }
    }

    fun open() {
        if (!state.value.saving && !state.value.open) mutable.update {
            it.copy(open = true, draft = it.saved, saveFailed = false, revision = it.revision + 1)
        }
    }

    fun dismiss() {
        if (!state.value.saving && state.value.open) mutable.update {
            it.copy(open = false, draft = it.saved, saveFailed = false, revision = it.revision + 1)
        }
    }

    fun edit(orientation: ReaderOrientation) {
        if (state.value.open && !state.value.saving && !state.value.loading && !state.value.loadFailed)
            mutable.update { it.copy(draft = orientation, saveFailed = false, revision = it.revision + 1) }
    }

    fun save() {
        val current = state.value
        if (!current.open || current.loading || current.loadFailed || current.saving) return
        mutable.update { it.copy(saving = true, saveFailed = false, revision = it.revision + 1) }
        viewModelScope.launch {
            try {
                repository.saveOrientation(current.draft)
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(saved = current.draft, saving = false, open = false,
                    revision = it.revision + 1) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(saveFailed = true, revision = it.revision + 1) }
            }
            finally { mutable.update { it.copy(saving = false) } }
        }
    }
}
