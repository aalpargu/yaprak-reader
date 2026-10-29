package com.yaprak.reader.feature.librarylabels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class LibraryLabelDraft(val id: Long, val kind: LibraryLabelKind, val source: String,
    val expected: List<LibraryLabelMember>, val target: String, val remove: Boolean = false)
internal data class LibraryLabelsState(val active: Boolean = false, val loading: Boolean = true,
    val snapshot: LibraryLabelSnapshot? = null, val failure: LibraryLabelFailure? = null,
    val kind: LibraryLabelKind = LibraryLabelKind.TAG, val query: String = "", val revision: Long = 0,
    val editor: LibraryLabelDraft? = null, val saving: Boolean = false,
    val writeFailure: LibraryLabelFailure? = null, val changedBooks: Int? = null) {
    fun fresh(draft: LibraryLabelDraft) = snapshot?.matching(draft.kind, draft.source) == draft.expected
    fun target(draft: LibraryLabelDraft): String? = runCatching { canonicalLibraryLabel(draft.target) }.getOrNull()
    fun canSave(draft: LibraryLabelDraft) = active && !loading && failure == null && !saving && fresh(draft) &&
        (draft.remove || target(draft)?.let { it != draft.source } == true)
}

internal class LibraryLabelsViewModel(private val repository: LibraryLabelsRepository) : ViewModel() {
    private val mutable = MutableStateFlow(LibraryLabelsState())
    val state = mutable.asStateFlow()
    private var owner: Any? = null
    private var sourceJob: Job? = null
    private var writeJob: Job? = null
    private var nextDraft = 0L
    private var sourceSerial = 0L
    private var writeSerial = 0L
    fun attach(token: Any) {
        if (owner === token) return
        sourceSerial++; writeSerial++
        sourceJob?.cancel(); writeJob?.cancel(); owner = token
        mutable.value = state.value.copy(active = false, loading = true, snapshot = null, saving = false,
            revision = state.value.revision + 1)
    }
    fun active(token: Any, value: Boolean) {
        if (owner !== token || state.value.active == value) return
        sourceSerial++; writeSerial++
        sourceJob?.cancel(); writeJob?.cancel()
        mutable.value = state.value.copy(active = value, loading = true, snapshot = null, saving = false,
            revision = state.value.revision + 1)
        if (value) observe(token)
    }
    fun detach(token: Any) { if (owner === token) { active(token, false); owner = null } }
    fun isActive(token: Any) = owner === token && state.value.active
    private fun current(token: Any, revision: Long) = isActive(token) && state.value.revision == revision && !state.value.saving
    fun retry(token: Any, revision: Long) { if (current(token, revision) && state.value.failure != null) observe(token) }
    private fun observe(token: Any) {
        val serial = ++sourceSerial
        sourceJob?.cancel()
        mutable.value = state.value.copy(loading = true, snapshot = null, failure = null, revision = state.value.revision + 1)
        sourceJob = viewModelScope.launch {
            try { repository.observe().collect { snapshot ->
                if (isActive(token) && sourceSerial == serial) mutable.value = state.value.copy(loading = false, snapshot = snapshot,
                    failure = null, revision = state.value.revision + 1)
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (isActive(token) && sourceSerial == serial) mutable.value = state.value.copy(loading = false, snapshot = null,
                    failure = classify(failure), revision = state.value.revision + 1)
            }
        }
    }
    fun kind(token: Any, revision: Long, kind: LibraryLabelKind) {
        if (current(token, revision) && state.value.editor == null)
            mutable.value = state.value.copy(kind = kind, query = "", changedBooks = null, revision = revision + 1)
    }
    fun query(token: Any, revision: Long, query: String) {
        if (current(token, revision) && state.value.editor == null)
            mutable.value = state.value.copy(query = query.take(200), revision = revision + 1)
    }
    fun open(token: Any, revision: Long, name: String) {
        val now = state.value
        if (!current(token, revision) || now.loading || now.failure != null || now.editor != null) return
        val snapshot = now.snapshot ?: return
        if (snapshot.entries(now.kind).none { it.name == name }) return
        val expected = snapshot.matching(now.kind, name)
        mutable.value = now.copy(editor = LibraryLabelDraft(++nextDraft, now.kind, name, expected, name),
            writeFailure = null, changedBooks = null, revision = revision + 1)
    }
    fun edit(token: Any, id: Long, revision: Long, target: String? = null, remove: Boolean? = null) {
        val draft = state.value.editor ?: return
        if (!current(token, revision) || draft.id != id) return
        mutable.value = state.value.copy(editor = draft.copy(target = target?.take(200) ?: draft.target,
            remove = remove ?: draft.remove), writeFailure = null, revision = revision + 1)
    }
    fun dismiss(token: Any, id: Long, revision: Long) {
        if (current(token, revision) && state.value.editor?.id == id)
            mutable.value = state.value.copy(editor = null, writeFailure = null, revision = revision + 1)
    }
    fun save(token: Any, id: Long, revision: Long) {
        val now = state.value; val draft = now.editor ?: return
        if (!current(token, revision) || draft.id != id || !now.canSave(draft)) return
        val target = if (draft.remove) null else now.target(draft) ?: return
        val serial = ++writeSerial
        mutable.value = now.copy(saving = true, writeFailure = null, revision = revision + 1)
        writeJob = viewModelScope.launch {
            try {
                val affected = repository.change(draft.kind, draft.source, target, draft.expected)
                require(affected == draft.expected.size)
                if (isActive(token) && writeSerial == serial && state.value.editor?.id == id)
                    mutable.value = state.value.copy(editor = null, saving = false, changedBooks = affected,
                        revision = state.value.revision + 1)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (isActive(token) && writeSerial == serial && state.value.editor?.id == id)
                    mutable.value = state.value.copy(saving = false, writeFailure = classify(failure),
                        revision = state.value.revision + 1)
            } finally {
                if (owner === token && writeSerial == serial && state.value.editor?.id == id && state.value.saving)
                    mutable.value = state.value.copy(saving = false, revision = state.value.revision + 1)
            }
        }
    }
    private fun classify(failure: Exception) = (failure as? LibraryLabelException)?.reason ?: LibraryLabelFailure.SOURCE
}
