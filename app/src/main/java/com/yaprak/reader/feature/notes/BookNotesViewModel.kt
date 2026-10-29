package com.yaprak.reader.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.notes.BookNote
import com.yaprak.reader.domain.notes.BookNoteRepository
import com.yaprak.reader.domain.notes.MAX_BOOK_NOTE_LENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class BookNotesState(
    val entries: List<BookNote> = emptyList(),
    val query: String = "",
    val sort: BookNoteSort = BookNoteSort.RECORD_ORDER,
    val draft: String = "",
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val pendingDelete: BookNote? = null,
    val pendingEdit: BookNote? = null,
    val editDraft: String = "",
) {
    val visibleEntries: List<BookNote> get() = sortBookNotes(searchBookNotes(entries, query), sort)
    val canSave: Boolean get() = !loading && !loadFailed && !busy && pendingDelete == null && pendingEdit == null &&
        draft.trim().length in 1..MAX_BOOK_NOTE_LENGTH
    val canUpdate: Boolean get() = !loading && !loadFailed && !busy && pendingDelete == null &&
        pendingEdit != null && editDraft.trim().length in 1..MAX_BOOK_NOTE_LENGTH &&
        editDraft.trim() != pendingEdit.text
}

internal class BookNotesViewModel(
    private val bookId: String,
    private val contentSha256: String,
    private val repository: BookNoteRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(BookNotesState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null

    init { reload() }

    fun search(query: String) { mutable.update { it.copy(query = limitBookNoteSearchQuery(query)) } }

    fun clearSearch() { mutable.update { it.copy(query = "") } }

    fun setSort(sort: BookNoteSort) { mutable.update { it.copy(sort = sort) } }

    fun reload() {
        if (state.value.busy) return
        observation?.cancel()
        mutable.update { it.copy(loading = true, loadFailed = false, message = null) }
        observation = viewModelScope.launch {
            try {
                repository.observe(bookId, contentSha256).collect { entries ->
                    mutable.update { it.copy(entries = entries, loading = false, loadFailed = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun editDraft(value: String) {
        if (!state.value.busy && state.value.pendingEdit == null && state.value.pendingDelete == null)
            mutable.update { it.copy(draft = value, message = null) }
    }

    fun prefillDraft(text: String) {
        val trimmed = text.trim()
        if (trimmed.length !in 1..MAX_BOOK_NOTE_LENGTH) return
        mutable.update { current ->
            if (current.busy || current.pendingDelete != null || current.pendingEdit != null) current
            else current.copy(draft = trimmed, message = null)
        }
    }

    fun clearDraft() {
        if (!state.value.busy && state.value.pendingEdit == null && state.value.pendingDelete == null)
            mutable.update { it.copy(draft = "", message = null) }
    }

    fun clearSession() {
        if (!state.value.busy) mutable.update { it.copy(query = "", sort = BookNoteSort.RECORD_ORDER,
            draft = "", editDraft = "",
            pendingEdit = null, pendingDelete = null, message = null) }
    }

    fun add() = saveNote(null)

    fun addAtPdfPage(capturePdfPage: () -> Int?) {
        if (!state.value.canSave) return
        val pageIndex = try {
            capturePdfPage()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (pageIndex == null || pageIndex < 0 || pageIndex == Int.MAX_VALUE) {
            mutable.update { it.copy(message = "Görüntülenen PDF sayfası doğrulanamadı. Not kaydedilmedi.") }
            return
        }
        saveNote(pageIndex)
    }

    private fun saveNote(pdfPageIndex: Int?) {
        val current = state.value
        if (!current.canSave) return
        val text = current.draft.trim()
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                if (pdfPageIndex == null) repository.add(bookId, contentSha256, text)
                else repository.addAtPdfPage(bookId, contentSha256, text, pdfPageIndex)
                mutable.update { it.copy(busy = false, draft = "", message = "Not kaydedildi.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(busy = false, message = "Not kaydedilemedi. Yeniden deneyebilirsin.") }
            }
        }
    }

    fun requestDelete(id: String) {
        val current = state.value
        if (current.busy || current.loading || current.loadFailed ||
            current.pendingDelete != null || current.pendingEdit != null) return
        mutable.update { state ->
            state.copy(pendingDelete = state.entries.firstOrNull { it.id == id }, message = null)
        }
    }

    fun cancelDelete() {
        if (!state.value.busy) mutable.update { it.copy(pendingDelete = null, message = null) }
    }

    fun delete() {
        val current = state.value
        val entry = current.pendingDelete ?: return
        if (current.busy || current.loading || current.loadFailed || current.pendingEdit != null) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                repository.remove(bookId, contentSha256, entry.id)
                mutable.update { it.copy(busy = false, pendingDelete = null, message = "Not silindi.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(busy = false, message = "Not silinemedi. Yeniden deneyebilirsin.") }
            }
        }
    }

    fun requestEdit(id: String) {
        val current = state.value
        if (current.busy || current.loading || current.loadFailed ||
            current.pendingDelete != null || current.pendingEdit != null) return
        mutable.update { currentState ->
            val entry = currentState.entries.firstOrNull { it.id == id } ?: return@update currentState
            currentState.copy(pendingEdit = entry, editDraft = entry.text, message = null)
        }
    }

    fun editSelectedDraft(value: String) {
        if (state.value.pendingEdit != null && !state.value.busy)
            mutable.update { it.copy(editDraft = value, message = null) }
    }

    fun cancelEdit() {
        if (!state.value.busy) mutable.update { it.copy(pendingEdit = null, editDraft = "", message = null) }
    }

    fun saveEdit() {
        val current = state.value
        if (!current.canUpdate) return
        val entry = current.pendingEdit ?: return
        val text = current.editDraft.trim()
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                repository.update(bookId, contentSha256, entry.id, text)
                mutable.update { it.copy(
                    entries = it.entries.map { existing -> if (existing.id == entry.id) existing.copy(text = text) else existing },
                    busy = false, pendingEdit = null, editDraft = "", message = "Not düzenlendi.",
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(busy = false, message = "Not düzenlenemedi. Yeniden deneyebilirsin.") }
            }
        }
    }
}
