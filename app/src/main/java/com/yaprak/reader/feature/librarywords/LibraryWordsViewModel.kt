package com.yaprak.reader.feature.librarywords

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.vocabulary.*
import com.yaprak.reader.feature.vocabulary.SavedWordPractice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LibraryWordsState(
    val query: String = "", val entries: List<LibraryWord> = emptyList(), val totalCount: Int = 0,
    val active: Boolean = false, val loading: Boolean = true, val failed: Boolean = false,
    val practice: SavedWordPractice? = null, val busy: Boolean = false, val feedback: String? = null,
    val revision: Long = 0,
) {
    val actionsEnabled get() = active && !loading && !failed && !busy
    val current get() = practice?.current(entries.map { it.word })?.let { card -> entries.singleOrNull { it.word == card } }
}

internal class LibraryWordsViewModel(private val source: LibraryWordsRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(LibraryWordsState(query = limitLibraryWordQuery(saved.get<Any?>(QUERY) as? String ?: "")))
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var action: Job? = null
    private var generation = 0L
    private var actionSerial = 0L
    private var owner: Any? = null
    init { saved[QUERY] = state.value.query }
    private fun update(block: (LibraryWordsState) -> LibraryWordsState) {
        val current = state.value
        mutable.value = block(current).copy(revision = current.revision + 1)
    }
    fun attach(value: Any) { if (owner !== value) { owner = value; active(false) } }
    fun detach(value: Any) { if (owner === value) { owner = null; active(false) } }
    fun active(value: Any, enabled: Boolean) { if (owner === value) active(enabled) }
    fun isActive(value: Any) = owner === value && state.value.active
    fun active(enabled: Boolean) {
        if (state.value.active == enabled) return
        update { it.copy(active = enabled) }
        observe(false)
    }
    fun search(query: String) {
        val limited = limitLibraryWordQuery(query)
        if (limited == state.value.query) return
        saved[QUERY] = limited
        update { it.copy(query = limited) }
        observe(true)
    }
    fun reload(revision: Long) { if (state.value.active && state.value.revision == revision) observe(false) }
    private fun observe(debounce: Boolean) {
        val request = ++generation
        observation?.cancel(); action?.cancel(); actionSerial++
        update { it.copy(entries = emptyList(), totalCount = 0, loading = true, failed = false, busy = false, practice = null, feedback = null) }
        if (!state.value.active) return
        val query = state.value.query
        observation = viewModelScope.launch {
            try {
                if (debounce) delay(250)
                source.observe(query).collect { result ->
                    ensureActive()
                    if (request != generation || !state.value.active || state.value.query != query) return@collect
                    val entries = result.entries.toList()
                    check(entries.size <= MAX_LIBRARY_WORD_RESULTS && result.totalCount >= entries.size &&
                        entries.map { it.word.id }.distinct().size == entries.size)
                    action?.cancel(); actionSerial++
                    update { it.copy(entries = entries, totalCount = result.totalCount, loading = false, failed = false, busy = false,
                        practice = it.practice?.reconcile(entries.map { entry -> entry.word }), feedback = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == generation) {
                    action?.cancel(); actionSerial++
                    update { it.copy(entries = emptyList(), totalCount = 0, failed = true, loading = false, practice = null, busy = false) }
                }
            }
        }
    }
    fun practice(revision: Long, command: String) {
        val current = state.value
        if (!current.actionsEnabled || revision != current.revision) return
        val cards = current.entries.map { it.word }
        val next = when (command) {
            "start" -> SavedWordPractice.start(cards)
            "stop" -> null
            "reveal" -> current.practice?.reveal(cards)
            "next" -> current.practice?.move(cards, 1)
            "previous" -> current.practice?.move(cards, -1)
            "shuffle" -> current.practice?.shuffle(cards)
            else -> return
        }
        update { it.copy(practice = next, feedback = null) }
    }
    fun open(revision: Long, entry: LibraryWord, launch: (String) -> Unit) {
        val current = state.value
        if (!current.actionsEnabled || current.revision != revision || current.entries.singleOrNull { it.word.id == entry.word.id } != entry) return
        val request = ++actionSerial
        val observationGeneration = generation
        update { it.copy(busy = true, feedback = null) }
        action = viewModelScope.launch {
            try {
                val actual = source.find(entry.bookId, entry.contentSha256, entry.word.id)
                ensureActive()
                if (request != actionSerial || generation != observationGeneration || !state.value.active ||
                    state.value.entries.singleOrNull { it.word.id == entry.word.id } != entry) return@launch
                if (actual != entry) update { it.copy(busy = false, feedback = "changed") }
                else { launch(entry.bookId); update { it.copy(busy = false) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (request == actionSerial) update { it.copy(busy = false, feedback = "open") } }
        }
    }
    companion object { const val QUERY = "libraryWordsQuery" }
}
