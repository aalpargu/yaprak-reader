package com.yaprak.reader.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.settings.PdfNightSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PdfNightModeStatus {
    data object Loading : PdfNightModeStatus
    data object Ready : PdfNightModeStatus
    data object Saving : PdfNightModeStatus
    data object LoadFailed : PdfNightModeStatus
    data object SaveFailed : PdfNightModeStatus
}

data class PdfNightModeState(
    val enabled: Boolean = false,
    val status: PdfNightModeStatus = PdfNightModeStatus.Loading,
) {
    val canToggle: Boolean get() = status == PdfNightModeStatus.Ready ||
        status == PdfNightModeStatus.Saving || status == PdfNightModeStatus.SaveFailed
}

/** Coordinates the manual PDF display preference; applying it belongs to the reader surface. */
class PdfNightModeViewModel(
    private val repository: PdfNightSettingsRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val restored = savedState.get<Any?>(SAVED_STATE_KEY) as? Boolean
    private val mutable = MutableStateFlow(PdfNightModeState(enabled = restored ?: false))
    val state = mutable.asStateFlow()

    // A restored or newer user choice must win over a delayed initial preference read.
    private var chosen = restored != null
    private var loaded = false
    private var persisted: Boolean? = null
    private var revision = 0L
    private var loadJob: Job? = null
    private var saveJob: Job? = null

    init { load() }

    fun toggle() {
        chosen = true
        revision++
        val enabled = !state.value.enabled
        savedState[SAVED_STATE_KEY] = enabled
        mutable.update { it.copy(enabled = enabled) }
        saveIfNeeded()
    }

    fun retry() {
        when (state.value.status) {
            PdfNightModeStatus.LoadFailed -> load()
            PdfNightModeStatus.SaveFailed -> saveIfNeeded()
            else -> Unit
        }
    }

    // ReaderActivity restores only this preference because its format fragments cannot be restored.
    internal fun saveInstanceChoice(): Boolean? = savedState.get<Any?>(SAVED_STATE_KEY) as? Boolean

    private fun load() {
        if (loadJob?.isActive == true) return
        mutable.update { it.copy(status = PdfNightModeStatus.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val enabled = repository.pdfNightMode.first()
                currentCoroutineContext().ensureActive()
                persisted = enabled
                loaded = true
                if (!chosen) {
                    savedState[SAVED_STATE_KEY] = enabled
                    mutable.update { it.copy(enabled = enabled) }
                }
                saveIfNeeded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(status = PdfNightModeStatus.LoadFailed) }
            }
        }
    }

    private fun saveIfNeeded() {
        // Never replace an unreadable preference file with a fallback display value.
        if (!loaded) return
        if (saveJob?.isActive == true) {
            mutable.update { it.copy(status = PdfNightModeStatus.Saving) }
            return
        }
        if (state.value.enabled == persisted) {
            mutable.update { it.copy(status = PdfNightModeStatus.Ready) }
            return
        }
        mutable.update { it.copy(status = PdfNightModeStatus.Saving) }
        saveJob = viewModelScope.launch {
            while (true) {
                val writing = state.value.enabled
                val writingRevision = revision
                try {
                    repository.savePdfNightMode(writing)
                    currentCoroutineContext().ensureActive()
                    persisted = writing
                } catch (cancelled: CancellationException) {
                    persisted = null
                    throw cancelled
                } catch (_: Exception) {
                    // A provider can fail after committing, so retry must confirm the desired value.
                    persisted = null
                    if (revision == writingRevision) {
                        mutable.update { it.copy(status = PdfNightModeStatus.SaveFailed) }
                        return@launch
                    }
                    // An older failure cannot replace a newer queued choice with an error.
                    continue
                }
                if (state.value.enabled == persisted) {
                    mutable.update { it.copy(status = PdfNightModeStatus.Ready) }
                    return@launch
                }
            }
        }
    }

    internal companion object {
        const val SAVED_STATE_KEY = "pdfNightModeEnabled"
    }
}
