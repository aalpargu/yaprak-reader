package com.yaprak.reader.feature.wordreview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.vocabulary.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class WordReviewState(
    val active: Boolean = false, val loading: Boolean = true, val busy: Boolean = false,
    val snapshot: WordReviewSnapshot? = null, val practicing: Boolean = false, val revealed: Boolean = false,
    val failure: WordReviewFailure? = null, val receipt: WordReviewEvent? = null,
    val pending: WordReviewCommand? = null, val revision: Long = 0,
) {
    val current get() = if (practicing) snapshot?.cards?.firstOrNull() else null
    val actionsEnabled get() = active && !loading && !busy && snapshot != null
}

internal class WordReviewViewModel(private val repository: WordReviewRepository) : ViewModel() {
    private val mutable = MutableStateFlow(WordReviewState())
    val state = mutable.asStateFlow()
    private var owner: Any? = null
    private var job: Job? = null
    private var generation = 0L
    private fun update(block: (WordReviewState) -> WordReviewState) {
        val old = state.value; mutable.value = block(old).copy(revision = old.revision + 1)
    }
    fun attach(value: Any) { if (owner !== value) { owner = value; setActive(false) } }
    fun detach(value: Any) { if (owner === value) { owner = null; setActive(false) } }
    fun active(value: Any, enabled: Boolean) { if (owner === value) setActive(enabled) }
    fun isActive(value: Any) = owner === value && state.value.active
    private fun setActive(enabled: Boolean) {
        if (enabled == state.value.active) return
        generation++; job?.cancel()
        update { it.copy(active = enabled, loading = true, busy = false, snapshot = null, revealed = false, pending = null) }
        if (enabled) load()
    }
    fun reload(revision: Long) {
        if (state.value.active && !state.value.busy && state.value.revision == revision) load()
    }
    private fun load() {
        val request = ++generation; job?.cancel()
        update { it.copy(loading = true, failure = null, pending = null, revealed = false) }
        job = viewModelScope.launch {
            try {
                val snapshot = checkedSnapshot(repository.load())
                ensureActive()
                if (request == generation && state.value.active) update { it.copy(snapshot = snapshot, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (request == generation) update { it.copy(snapshot = null, loading = false,
                failure = (failure as? WordReviewException)?.reason ?: WordReviewFailure.SOURCE) } }
        }
    }
    fun command(revision: Long, command: String) {
        val old = state.value
        if (old.revision != revision || !old.actionsEnabled || old.pending != null) return
        when (command) {
            "start" -> if (!old.snapshot!!.cards.isEmpty()) update { it.copy(practicing = true, revealed = false, failure = null, receipt = null) }
            "stop" -> update { it.copy(practicing = false, revealed = false, failure = null) }
            "reveal" -> if (old.current != null && !old.revealed) update { it.copy(revealed = true) }
        }
    }
    fun rate(revision: Long, rating: WordRating) {
        val old = state.value
        if (old.revision != revision || !old.actionsEnabled || !old.revealed || old.current == null || old.pending != null) return
        record(WordReviewCommand(UUID.randomUUID().toString(), old.current!!, rating))
    }
    fun retry(revision: Long) {
        val old = state.value
        if (old.revision == revision && old.actionsEnabled && old.pending?.card == old.current) old.pending?.let(::record)
    }
    private fun record(command: WordReviewCommand) {
        val request = ++generation; job?.cancel()
        update { it.copy(busy = true, pending = command, failure = null, receipt = null) }
        job = viewModelScope.launch {
            try {
                val receipt = repository.rate(command)
                ensureActive(); validateWordReviewEvent(receipt); check(receipt.matches(command))
                if (request != generation || !state.value.active || state.value.pending !== command) return@launch
                update { it.copy(receipt = receipt, pending = null, revealed = false) }
                val snapshot = checkedSnapshot(repository.load())
                ensureActive()
                if (request == generation && state.value.active) update { it.copy(snapshot = snapshot, busy = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (request == generation) update { it.copy(busy = false,
                    failure = (failure as? WordReviewException)?.reason ?: WordReviewFailure.SOURCE,
                    snapshot = if (it.receipt != null) null else it.snapshot) }
            }
        }
    }
    private fun checkedSnapshot(value: WordReviewSnapshot): WordReviewSnapshot {
        check(value.cards.size <= MAX_WORD_REVIEW_CARDS && value.dueCount >= value.cards.size &&
            value.newCount in 0..value.dueCount && value.dueCount <= value.totalWords &&
            value.history.size <= MAX_WORD_REVIEW_HISTORY && value.historyCount in value.history.size..MAX_WORD_REVIEW_EVENTS &&
            value.cards.map { it.entry.word.id }.distinct().size == value.cards.size &&
            value.history.map { it.event.id }.distinct().size == value.history.size)
        value.cards.forEach { validateReviewWord(it.entry); it.schedule?.let(::validateWordSchedule) }
        value.history.forEach { validateWordReviewEvent(it.event) }
        return value.copy(cards = value.cards.toList(), history = value.history.toList())
    }
}
