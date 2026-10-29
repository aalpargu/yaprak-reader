package com.yaprak.reader.feature.librarynotes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.notes.LibraryNote
import com.yaprak.reader.domain.notes.LibraryNotesRepository
import com.yaprak.reader.domain.notes.limitLibraryNoteQuery
import com.yaprak.reader.domain.notes.LibraryNotesExporter
import com.yaprak.reader.domain.notes.LibraryNotesExportFormat
import com.yaprak.reader.domain.notes.MAX_LIBRARY_NOTES_EXPORT_RECORDS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class LibraryNoteFeedbackKind { COPIED, COPY_FAILED, OPEN_FAILED, CHANGED }

internal data class LibraryNoteFeedback(val note: LibraryNote, val kind: LibraryNoteFeedbackKind)

internal data class LibraryNotesExportChoice(val id: Long, val query: String,
    val entries: List<LibraryNote>, val totalCount: Int)

internal data class LibraryNotesState(
    val query: String = "",
    val entries: List<LibraryNote> = emptyList(),
    val totalCount: Int = 0,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val active: Boolean = false,
    val action: LibraryNote? = null,
    val feedback: LibraryNoteFeedback? = null,
) {
    val actionsEnabled: Boolean get() = active && !loading && !loadFailed && action == null
}

/** Cancels old requests immediately; the delay only postpones opening the new source. */
internal class LibraryNotesViewModel(
    private val repository: LibraryNotesRepository,
    private val savedStateHandle: SavedStateHandle,
    exporter: LibraryNotesExporter? = null,
) : ViewModel() {
    private val restoredQuery = limitLibraryNoteQuery(savedStateHandle.get<Any?>(QUERY) as? String ?: "")
    private val mutable = MutableStateFlow(LibraryNotesState(query = restoredQuery))
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var actionJob: Job? = null
    private var generation = 0L
    private var actionSerial = 0L
    private var lifecycleOwner: Any? = null
    val exports = LibraryNotesExportController(exporter, savedStateHandle, viewModelScope)
    private val mutableChoice = MutableStateFlow<LibraryNotesExportChoice?>(null)
    val exportChoice = mutableChoice.asStateFlow()
    private var choiceSerial = 0L

    init {
        savedStateHandle[QUERY] = restoredQuery
    }

    fun search(value: String) {
        if (exports.busy || mutableChoice.value != null || exports.notice.value != null) return
        val query = limitLibraryNoteQuery(value)
        if (query == state.value.query) return
        savedStateHandle[QUERY] = query
        mutable.update { it.copy(query = query) }
        observe(delaySearch = true)
    }

    fun reload() { if (!exports.busy && mutableChoice.value == null && exports.notice.value == null) observe(delaySearch = false) }

    fun showExportChoices(expected: LibraryNotesState) {
        val current = state.value
        if (!exports.available || exports.busy || mutableChoice.value != null || exports.notice.value != null || !current.actionsEnabled ||
            current.totalCount <= 0 || current != expected) return
        mutableChoice.value = LibraryNotesExportChoice(++choiceSerial, current.query, current.entries.toList(), current.totalCount)
    }

    fun exportChoiceCurrent(choice: LibraryNotesExportChoice): Boolean {
        val current = state.value
        return mutableChoice.value == choice && current.actionsEnabled && !exports.busy &&
            current.query == choice.query && current.entries == choice.entries && current.totalCount == choice.totalCount
    }

    fun dismissExportChoices(id: Long) { if (mutableChoice.value?.id == id) mutableChoice.value = null }

    fun selectExportFormat(id: Long, format: LibraryNotesExportFormat) {
        val choice = mutableChoice.value?.takeIf { it.id == id } ?: return
        if (!exportChoiceCurrent(choice) || choice.totalCount > MAX_LIBRARY_NOTES_EXPORT_RECORDS) return
        mutableChoice.value = null
        exports.prepare(choice.query, format)
    }

    /** A retained model accepts lifecycle changes only from its latest composed Activity. */
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
        if (!active) mutableChoice.value = null
        mutable.update { it.copy(active = active) }
        observe(delaySearch = false)
    }

    fun copy(note: LibraryNote, copyText: (String) -> Unit) =
        performAction(note, LibraryNoteFeedbackKind.COPY_FAILED, LibraryNoteFeedbackKind.COPIED) {
            copyText(it.text)
        }

    fun open(note: LibraryNote, openBook: (String) -> Unit) =
        performAction(note, LibraryNoteFeedbackKind.OPEN_FAILED, null) { openBook(it.bookId) }

    private fun observe(delaySearch: Boolean) {
        val request = ++generation
        cancelAction()
        observation?.cancel()
        val query = state.value.query
        mutable.update { it.copy(entries = emptyList(), totalCount = 0, loading = true,
            loadFailed = false, feedback = null) }
        observation = null
        if (!state.value.active) return
        observation = viewModelScope.launch {
            try {
                if (delaySearch) delay(SEARCH_DELAY_MILLIS)
                repository.observe(query).collect { result ->
                    if (request != generation || state.value.query != query) return@collect
                    val entries = result.entries.toList()
                    state.value.action?.let { if (it !in entries) cancelAction() }
                    mutable.update { current -> current.copy(entries = entries,
                        totalCount = result.totalCount, loading = false, loadFailed = false,
                        feedback = current.feedback?.takeIf { it.note in entries }) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation && state.value.query == query) {
                    cancelAction()
                    mutable.update { it.copy(entries = emptyList(), totalCount = 0,
                        loading = false, loadFailed = true, feedback = null) }
                }
            }
        }
    }

    private fun performAction(
        note: LibraryNote,
        failure: LibraryNoteFeedbackKind,
        success: LibraryNoteFeedbackKind?,
        effect: (LibraryNote) -> Unit,
    ) {
        val current = state.value
        if (exports.busy || mutableChoice.value != null || exports.notice.value != null) return
        if (!current.actionsEnabled || current.entries.singleOrNull { it.identity == note.identity } != note) return
        val requestGeneration = generation
        val serial = ++actionSerial
        mutable.update { it.copy(action = note, feedback = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val found = repository.find(note.bookId, note.contentSha256, note.id, note.source)
                if (!actionCurrent(note, requestGeneration, serial)) return@launch
                if (found != note) {
                    mutable.update { it.copy(feedback = LibraryNoteFeedback(note, LibraryNoteFeedbackKind.CHANGED)) }
                    return@launch
                }
                // No suspension between the last current-list check and the explicit UI effect.
                effect(note)
                if (actionCurrent(note, requestGeneration, serial)) {
                    mutable.update { it.copy(feedback = success?.let { kind -> LibraryNoteFeedback(note, kind) }) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (actionCurrent(note, requestGeneration, serial))
                    mutable.update { it.copy(feedback = LibraryNoteFeedback(note, failure)) }
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

    private fun actionCurrent(note: LibraryNote, requestGeneration: Long, serial: Long): Boolean {
        val current = state.value
        return requestGeneration == generation && serial == actionSerial && current.active &&
            !current.loading && !current.loadFailed && current.action == note &&
            current.entries.singleOrNull { it.identity == note.identity } == note
    }

    private fun cancelAction() {
        ++actionSerial
        actionJob?.cancel()
        actionJob = null
        mutable.update { it.copy(action = null, feedback = null) }
    }

    companion object {
        internal const val QUERY = "libraryNotesQuery"
        internal const val SEARCH_DELAY_MILLIS = 250L
    }
}
