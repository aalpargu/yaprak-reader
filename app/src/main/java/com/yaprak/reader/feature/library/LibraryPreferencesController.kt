package com.yaprak.reader.feature.library

import androidx.lifecycle.SavedStateHandle
import com.yaprak.reader.domain.library.LibraryPreferences
import com.yaprak.reader.domain.library.LibraryPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface LibraryPreferencesStatus {
    data object Loading : LibraryPreferencesStatus
    data object Ready : LibraryPreferencesStatus
    data object Saving : LibraryPreferencesStatus
    data object LoadFailed : LibraryPreferencesStatus
    data object SaveFailed : LibraryPreferencesStatus
}

/** Coordinates immediate screen choices with one serialized preference writer. */
internal class LibraryPreferencesController(
    private val repository: LibraryPreferencesRepository?,
    private val savedState: SavedStateHandle,
    private val scope: CoroutineScope,
) {
    private val restoredSort = LibrarySort.entries.firstOrNull { it.name == savedState.get<String>(SORT_KEY) }
    private val restoredLayout = LibraryLayout.entries.firstOrNull { it.name == savedState.get<String>(LAYOUT_KEY) }
    private val _sort = MutableStateFlow(restoredSort ?: LibrarySort.RECENT)
    val sort = _sort.asStateFlow()
    private val _layout = MutableStateFlow(restoredLayout ?: LibraryLayout.GRID)
    val layout = _layout.asStateFlow()
    private val _status = MutableStateFlow<LibraryPreferencesStatus>(
        if (repository == null) LibraryPreferencesStatus.Ready else LibraryPreferencesStatus.Loading,
    )
    val status = _status.asStateFlow()

    // Only valid restored values, or an explicit newer selection, override the first disk read.
    private var sortChosen = restoredSort != null
    private var layoutChosen = restoredLayout != null
    private var loaded = false
    private var persisted: LibraryPreferences? = null
    private var revision = 0L
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private val desired: LibraryPreferences get() = LibraryPreferences(layout.value, sort.value)

    init { if (repository != null) load() }

    fun chooseSort(sort: LibrarySort) {
        sortChosen = true
        revision++
        savedState[SORT_KEY] = sort.name
        _sort.value = sort
        saveIfNeeded()
    }

    fun chooseLayout(layout: LibraryLayout) {
        layoutChosen = true
        revision++
        savedState[LAYOUT_KEY] = layout.name
        _layout.value = layout
        saveIfNeeded()
    }

    fun retry() {
        when (status.value) {
            LibraryPreferencesStatus.LoadFailed -> load()
            LibraryPreferencesStatus.SaveFailed -> saveIfNeeded()
            else -> Unit
        }
    }

    private fun load() {
        val store = repository ?: return
        if (loadJob?.isActive == true) return
        _status.value = LibraryPreferencesStatus.Loading
        loadJob = scope.launch {
            try {
                val preferences = store.libraryPreferences.first()
                currentCoroutineContext().ensureActive()
                persisted = preferences
                loaded = true
                if (!sortChosen) {
                    savedState[SORT_KEY] = preferences.sort.name
                    _sort.value = preferences.sort
                }
                if (!layoutChosen) {
                    savedState[LAYOUT_KEY] = preferences.layout.name
                    _layout.value = preferences.layout
                }
                saveIfNeeded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _status.value = LibraryPreferencesStatus.LoadFailed
            }
        }
    }

    private fun saveIfNeeded() {
        val store = repository ?: return
        // A failed initial read must never turn fallback screen values into disk values.
        if (!loaded) return
        if (saveJob?.isActive == true) {
            _status.value = LibraryPreferencesStatus.Saving
            return
        }
        if (desired == persisted) {
            _status.value = LibraryPreferencesStatus.Ready
            return
        }
        _status.value = LibraryPreferencesStatus.Saving
        saveJob = scope.launch {
            while (true) {
                val writing = desired
                val writingRevision = revision
                try {
                    store.saveLibraryPreferences(writing)
                    currentCoroutineContext().ensureActive()
                    persisted = writing
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Retry must confirm the latest pair, even if it equals the original disk value.
                    persisted = null
                    if (revision == writingRevision) {
                        _status.value = LibraryPreferencesStatus.SaveFailed
                        return@launch
                    }
                    // The failed write belongs to an older selection; attempt the latest one.
                    continue
                }
                if (desired == persisted) {
                    _status.value = LibraryPreferencesStatus.Ready
                    return@launch
                }
            }
        }
    }

    private companion object {
        const val SORT_KEY = "librarySort"
        const val LAYOUT_KEY = "libraryLayout"
    }
}
