package com.yaprak.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.settings.ScreenAwakeSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScreenAwakeState(
    val saved: Boolean = false,
    val draft: Boolean = false,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val saveFailed: Boolean = false,
    val saving: Boolean = false,
    val open: Boolean = false,
)

/** Owns preference editing only; the foreground Activity applies the window flag. */
class ScreenAwakeViewModel(private val repository: ScreenAwakeSettingsRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ScreenAwakeState())
    val state = mutable.asStateFlow()
    private var loadingJob: Job? = null

    init { reload() }

    fun reload() {
        if (state.value.saving) return
        loadingJob?.cancel()
        mutable.update { it.copy(loading = true, loadFailed = false) }
        loadingJob = viewModelScope.launch {
            try {
                repository.keepScreenOn.collect { saved ->
                    mutable.update {
                        it.copy(saved = saved, draft = if (it.open && !it.loading) it.draft else saved,
                            loading = false, loadFailed = false)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loading = false, loadFailed = true) } }
        }
    }

    fun open() {
        if (!state.value.saving) mutable.update { it.copy(open = true, draft = it.saved, saveFailed = false) }
    }

    fun dismiss() {
        if (!state.value.saving) mutable.update { it.copy(open = false, draft = it.saved, saveFailed = false) }
    }

    fun edit(enabled: Boolean) {
        if (state.value.open && !state.value.saving && !state.value.loading && !state.value.loadFailed)
            mutable.update { it.copy(draft = enabled, saveFailed = false) }
    }

    fun save() {
        val current = state.value
        if (!current.open || current.loading || current.loadFailed || current.saving) return
        mutable.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                repository.saveKeepScreenOn(current.draft)
                mutable.update { it.copy(saved = current.draft, saving = false, open = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(saving = false, saveFailed = true) } }
        }
    }
}
