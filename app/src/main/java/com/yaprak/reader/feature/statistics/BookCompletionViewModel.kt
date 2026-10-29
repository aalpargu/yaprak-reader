package com.yaprak.reader.feature.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.statistics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

internal data class BookCompletionState(val loading: Boolean = true,
    val snapshot: BookCompletionSnapshot? = null, val failure: BookCompletionFailure? = null)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class BookCompletionViewModel(repository: BookCompletionRepository) : ViewModel() {
    private val reload = MutableStateFlow(0L)
    val state = reload.flatMapLatest {
        repository.observe().map { snapshot ->
            require(snapshot.total in 0..MAX_BOOK_COMPLETION_EVENTS &&
                snapshot.entries.size == minOf(snapshot.total, BOOK_COMPLETION_VISIBLE_LIMIT))
            snapshot.entries.forEach { validateBookCompletionEvent(it.event) }
            require(snapshot.entries.map { it.event.id }.distinct().size == snapshot.entries.size)
            require(snapshot.entries == snapshot.entries.sortedWith(
                compareByDescending<BookCompletionEntry> { it.event.completedAt }.thenByDescending { it.event.id }))
            BookCompletionState(loading = false, snapshot = snapshot)
        }.onStart { emit(BookCompletionState()) }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(BookCompletionState(loading = false, failure =
                (failure as? BookCompletionException)?.reason ?: BookCompletionFailure.SOURCE))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), BookCompletionState())
    fun retry() { reload.value += 1 }
}