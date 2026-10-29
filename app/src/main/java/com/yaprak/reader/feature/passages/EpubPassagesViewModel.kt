package com.yaprak.reader.feature.passages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.domain.passages.EpubPassageRepository
import com.yaprak.reader.domain.passages.EpubHighlightColor
import java.util.UUID
import com.yaprak.reader.reader.EpubSourceAnchor
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceRangeAnchors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PassageDraft(val text: String, val anchor: EpubSourceAnchor)

internal data class EpubPassagesState(
    val entries: List<EpubPassage> = emptyList(),
    val query: String = "",
    val sort: EpubPassageSort = EpubPassageSort.RECORD_ORDER,
    val draft: PassageDraft? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val pendingDelete: EpubPassage? = null,
    val recentlySaved: Set<EpubSourceAnchor> = emptySet(),
    val pendingEdit: EpubPassage? = null,
    val editColor: EpubHighlightColor? = null,
    val editNote: String = "",
    val sessionId: String = UUID.randomUUID().toString(),
    val editRevision: Long = 0,
) {
    val visibleEntries: List<EpubPassage> get() = sortEpubPassages(searchEpubPassages(entries, query), sort)
    val alreadySaved: Boolean get() = draft?.let { candidate ->
        candidate.anchor in recentlySaved || entries.any { it.anchor == candidate.anchor }
    } ?: false
    val canSave: Boolean get() = !loading && !loadFailed && !busy && pendingDelete == null && pendingEdit == null &&
        draft != null && !alreadySaved
    val editChanged: Boolean get() = pendingEdit?.let { expected -> entries.singleOrNull { it.id == expected.id } != expected } ?: false
    val deleteChanged: Boolean get() = pendingDelete?.let { expected -> entries.singleOrNull { it.id == expected.id } != expected } ?: false
    val canUpdate: Boolean get() = !loading && !loadFailed && !busy && pendingDelete == null && pendingEdit != null &&
        !editChanged && validAttachedNoteDraft(editNote) &&
        (editColor != pendingEdit.highlightColor || editNote != (pendingEdit.attachedNote ?: ""))
}

/** An explicit, local save of source-matched selected text. No rendered highlight is inferred. */
internal class EpubPassagesViewModel(
    private val bookId: String,
    private val contentSha256: String,
    private val repository: EpubPassageRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(EpubPassagesState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var discardAfterBusy = false
    private var loadGeneration = 0L

    init { reload() }

    fun search(query: String) { if (canFilter()) mutable.update { it.copy(query = limitEpubPassageSearchQuery(query)) } }

    fun clearSearch() { if (canFilter()) mutable.update { it.copy(query = "") } }

    fun setSort(sort: EpubPassageSort) { if (canFilter()) mutable.update { it.copy(sort = sort) } }

    // A delete keeps its full expected record even when its display query changes.
    private fun canFilter() = !state.value.busy && state.value.pendingEdit == null

    private fun canBrowse() = !state.value.busy && state.value.pendingEdit == null && state.value.pendingDelete == null

    fun reload() {
        if (state.value.busy) return
        val generation = ++loadGeneration
        observation?.cancel()
        mutable.update { it.copy(loading = true, loadFailed = false, message = null) }
        observation = viewModelScope.launch {
            try {
                repository.observe(bookId, contentSha256).collect { entries ->
                    currentCoroutineContext().ensureActive()
                    if (generation == loadGeneration) mutable.update { it.copy(entries = entries, loading = false, loadFailed = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == loadGeneration) mutable.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun prefill(text: String, anchor: EpubSourceAnchor) {
        if (text.isBlank() || text.length !in 1..4096 || anchor.resourceHref.isBlank() ||
            !SourceRangeAnchors.validUtf16(text) || !when (anchor) {
                is SourceTextAnchor -> anchor.start >= 0 && anchor.end > anchor.start &&
                    anchor.end <= 4096 && anchor.end - anchor.start == text.length
                is SourceRangeAnchor -> SourceRangeAnchors.isValid(anchor)
            }) return
        mutable.update { current ->
            if (current.busy || current.pendingDelete != null || current.pendingEdit != null) current
            else current.copy(draft = PassageDraft(text, anchor), message = null)
        }
    }

    fun startSession(sessionId: String) {
        clearSession()
        mutable.update { it.copy(sessionId = sessionId) }
    }

    fun clearSession(expectedSession: String? = null) {
        if (expectedSession != null && expectedSession != state.value.sessionId) return
        if (state.value.busy) discardAfterBusy = true
        mutable.update { it.copy(query = "", sort = EpubPassageSort.RECORD_ORDER,
            draft = null, pendingDelete = null, pendingEdit = null, editColor = null, editNote = "", message = null,
            sessionId = UUID.randomUUID().toString(), editRevision = it.editRevision + 1) }
    }

    private fun finishWrite(change: (EpubPassagesState) -> EpubPassagesState) {
        val discard = discardAfterBusy
        mutable.update { current ->
            val next = change(current)
            if (discard) next.copy(query = "", sort = EpubPassageSort.RECORD_ORDER,
                draft = null, pendingDelete = null, pendingEdit = null, editColor = null, editNote = "", message = null) else next
        }
        discardAfterBusy = false
    }

    fun save() {
        val current = state.value
        if (!current.canSave) return
        val draft = current.draft ?: return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                repository.add(bookId, contentSha256, draft.text, draft.anchor)
                currentCoroutineContext().ensureActive()
                finishWrite { it.copy(busy = false, draft = null, message = "Pasaj kaydedildi.",
                    recentlySaved = it.recentlySaved + draft.anchor) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                finishWrite { it.copy(busy = false, message = "Pasaj kaydedilemedi. Yeniden deneyebilirsin.") }
            } finally { if (state.value.busy) finishWrite { it.copy(busy = false) } }
        }
    }

    fun requestDelete(id: String) {
        val current = state.value
        if (current.busy || current.loading || current.loadFailed || current.pendingDelete != null || current.pendingEdit != null) return
        mutable.update { it.copy(pendingDelete = it.entries.firstOrNull { entry -> entry.id == id }, message = null) }
    }

    fun cancelDelete() {
        if (!state.value.busy) mutable.update { it.copy(pendingDelete = null, message = null) }
    }

    fun delete() {
        val current = state.value
        val entry = current.pendingDelete ?: return
        if (current.busy || current.loading || current.loadFailed || current.deleteChanged) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                repository.removeExpected(bookId, contentSha256, entry)
                currentCoroutineContext().ensureActive()
                finishWrite { it.copy(busy = false, pendingDelete = null, message = "Pasaj silindi.",
                    recentlySaved = it.recentlySaved - entry.anchor) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                finishWrite { it.copy(busy = false, message = "Pasaj silinemedi. Yeniden deneyebilirsin.") }
            } finally { if (state.value.busy) finishWrite { it.copy(busy = false) } }
        }
    }

    fun requestEdit(expected: EpubPassage) {
        val current = state.value
        if (!canBrowse() || current.loading || current.loadFailed || current.entries.singleOrNull { it.id == expected.id } != expected) return
        mutable.update { it.copy(pendingEdit = expected, editColor = expected.highlightColor,
            editNote = expected.attachedNote ?: "", message = null, editRevision = it.editRevision + 1) }
    }
    fun editColor(color: EpubHighlightColor?) {
        if (!state.value.busy && state.value.pendingEdit != null && !state.value.editChanged)
            mutable.update { it.copy(editColor = color, message = null, editRevision = it.editRevision + 1) }
    }
    fun editNote(note: String) {
        if (!state.value.busy && state.value.pendingEdit != null && !state.value.editChanged)
            mutable.update { it.copy(editNote = note, message = null, editRevision = it.editRevision + 1) }
    }
    fun cancelEdit() {
        if (!state.value.busy) mutable.update { it.copy(pendingEdit = null, editColor = null, editNote = "",
            message = null, editRevision = it.editRevision + 1) }
    }
    fun update() {
        val current = state.value
        if (!current.canUpdate) return
        val expected = current.pendingEdit ?: return
        mutable.update { it.copy(busy = true, message = null, editRevision = it.editRevision + 1) }
        viewModelScope.launch {
            try {
                repository.updateAnnotation(bookId, contentSha256, expected, current.editColor,
                    current.editNote.takeUnless(String::isEmpty))
                currentCoroutineContext().ensureActive()
                finishWrite { it.copy(busy = false, pendingEdit = null, editColor = null, editNote = "",
                    message = "Vurgu ve bağlı not kaydedildi.", editRevision = it.editRevision + 1) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                finishWrite { it.copy(busy = false, message = "Vurgu ve bağlı not kaydedilemedi. Yeniden deneyebilirsin.",
                    editRevision = it.editRevision + 1) }
            } finally { if (state.value.busy) finishWrite { it.copy(busy = false) } }
        }
    }
}
