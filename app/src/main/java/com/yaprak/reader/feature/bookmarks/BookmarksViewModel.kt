package com.yaprak.reader.feature.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.bookmarks.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookmarksState(
    val entries: List<Bookmark> = emptyList(),
    val query: String = "",
    val sort: BookmarkSort = BookmarkSort.RECORD_ORDER,
    val target: BookmarkTarget? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val pendingDelete: Bookmark? = null,
    val pendingRename: Bookmark? = null,
    val renameDraft: String = "",
) {
    val visibleEntries: List<Bookmark> get() = sortBookmarks(searchBookmarks(entries, query), sort)
    val canAdd: Boolean get() = !loading && !loadFailed && !busy && target != null && entries.none { it.target.location == target.location }
    val canRename: Boolean get() = !loading && !loadFailed && !busy && pendingRename != null && renameDraft.trim().length in 1..200
}

class BookmarksViewModel(private val bookId: String, private val hash: String, private val repository: BookmarkRepository) : ViewModel() {
    private val mutable = MutableStateFlow(BookmarksState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    init { reload() }

    fun search(query: String) { mutable.update { it.copy(query = limitBookmarkSearchQuery(query)) } }

    fun clearSearch() { mutable.update { it.copy(query = "") } }

    fun setSort(sort: BookmarkSort) { mutable.update { it.copy(sort = sort) } }

    fun clearSession() {
        if (!state.value.busy) mutable.update {
            it.copy(query = "", sort = BookmarkSort.RECORD_ORDER, pendingDelete = null, pendingRename = null, renameDraft = "", message = null)
        }
    }

    fun reload() {
        observation?.cancel()
        mutable.update { it.copy(loading = true, loadFailed = false) }
        observation = viewModelScope.launch {
            try {
                repository.observe(bookId, hash).collect { entries -> mutable.update { it.copy(entries = entries, loading = false, loadFailed = false) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loading = false, loadFailed = true) } }
        }
    }

    fun selectPosition(target: BookmarkTarget?) { if (!state.value.busy) mutable.update {
        it.copy(target = target, message = null, pendingDelete = null, pendingRename = null, renameDraft = "")
    } }
    fun add() {
        if (!state.value.canAdd) return
        val target = state.value.target ?: return
        mutate("Yer imi kaydedildi.") { repository.add(bookId, hash, target) }
    }
    fun requestDelete(id: String) {
        if (!state.value.busy && !state.value.loading && !state.value.loadFailed) mutable.update { state ->
            state.copy(pendingDelete = state.entries.firstOrNull { it.id == id }, pendingRename = null, renameDraft = "")
        }
    }
    fun cancelDelete() { if (!state.value.busy) mutable.update { it.copy(pendingDelete = null) } }
    fun delete() {
        val entry = state.value.pendingDelete ?: return
        if (state.value.busy) return
        mutate("Yer imi silindi.") { repository.remove(bookId, hash, entry.id) }
    }
    fun requestRename(id: String) {
        if (state.value.busy || state.value.loading || state.value.loadFailed) return
        mutable.update { state ->
            val entry = state.entries.firstOrNull { it.id == id } ?: return@update state
            state.copy(pendingRename = entry, renameDraft = entry.target.label, pendingDelete = null, message = null)
        }
    }
    fun editRename(value: String) {
        if (state.value.pendingRename != null && !state.value.busy)
            mutable.update { it.copy(renameDraft = value, message = null) }
    }
    fun cancelRename() {
        if (!state.value.busy) mutable.update { it.copy(pendingRename = null, renameDraft = "", message = null) }
    }
    fun saveRename() {
        val current = state.value
        if (!current.canRename) return
        val entry = current.pendingRename ?: return
        mutate("Yer imi adı değiştirildi.") { repository.rename(bookId, hash, entry.id, current.renameDraft.trim()) }
    }
    private fun mutate(success: String, action: suspend () -> Unit) {
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { action(); mutable.update { it.copy(busy = false, message = success, pendingDelete = null, pendingRename = null, renameDraft = "") } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(busy = false, message = "İşlem tamamlanamadı. Yeniden deneyebilirsin.") } }
        }
    }
}
