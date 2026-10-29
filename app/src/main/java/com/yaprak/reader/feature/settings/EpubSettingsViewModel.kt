package com.yaprak.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.EpubSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EpubSettingsState(
    val saved: EpubSettings = EpubSettings(),
    val draft: EpubSettings = EpubSettings(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val saveFailed: Boolean = false,
    val saving: Boolean = false,
    val open: Boolean = false,
)

/** A separate editor keeps preferences and failed writes out of the reading-position VM. */
class EpubSettingsViewModel(private val repository: EpubSettingsRepository) : ViewModel() {
    private val mutable = MutableStateFlow(EpubSettingsState())
    val state = mutable.asStateFlow()
    private var loadingJob: Job? = null

    init { reload() }

    fun reload() {
        loadingJob?.cancel()
        mutable.update { it.copy(loading = true, loadFailed = false) }
        loadingJob = viewModelScope.launch {
            try {
                repository.settings.collect { saved ->
                    mutable.update { it.copy(saved = saved, draft = if (it.open && !it.loading) it.draft else saved, loading = false, loadFailed = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loading = false, loadFailed = true) } }
        }
    }

    fun open() { mutable.update { it.copy(open = true, draft = it.saved, saveFailed = false) } }
    fun dismiss() { if (!state.value.saving) mutable.update { it.copy(open = false, draft = it.saved, saveFailed = false) } }
    fun edit(value: EpubSettings) {
        if (state.value.open && !state.value.saving && !state.value.loading && !state.value.loadFailed)
            mutable.update { it.copy(draft = value, saveFailed = false) }
    }
    fun reset() = edit(EpubSettings())

    fun save() {
        val current = state.value
        if (!current.open || current.loading || current.loadFailed || current.saving) return
        mutable.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                repository.save(current.draft)
                mutable.update { it.copy(saved = current.draft, saving = false, open = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(saving = false, saveFailed = true) } }
        }
    }
}
