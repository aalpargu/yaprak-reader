package com.yaprak.reader.feature.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.statistics.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class DailyReadingGoalReadFailure { INVALID_VALUE, SOURCE }

internal data class DailyReadingGoalDraft(
    val requestId: String,
    val revision: Long = 0,
    val enabled: Boolean,
    val minutesText: String,
) {
    val minutes: Int? get() = parseDailyReadingGoalMinutes(minutesText)
    val valid: Boolean get() = !enabled || minutes != null
}

internal data class DailyReadingGoalState(
    val savedMinutes: Int? = null,
    val loading: Boolean = true,
    val readFailure: DailyReadingGoalReadFailure? = null,
    val saving: Boolean = false,
    val writeFailed: Boolean = false,
    val active: Boolean = false,
    val revision: Long = 0,
    val editor: DailyReadingGoalDraft? = null,
) {
    val known: Boolean get() = !loading && readFailure == null
}

/** An optional device preference. It never creates or changes reading-duration records. */
internal class DailyReadingGoalViewModel(private val repository: DailyReadingGoalRepository) : ViewModel() {
    private val mutable = MutableStateFlow(DailyReadingGoalState())
    val state = mutable.asStateFlow()
    private var owner: Any? = null
    private var observation: Job? = null
    private var observationGeneration = 0L

    fun attachLifecycleOwner(value: Any) {
        if (owner === value) return
        owner = value
        deactivate()
    }
    fun setLifecycleOwnerActive(value: Any, active: Boolean) {
        if (owner !== value || state.value.active == active) return
        if (active) {
            change { it.copy(active = true) }
            observe()
        } else deactivate()
    }
    fun isLifecycleOwnerActive(value: Any) = owner === value && state.value.active
    fun detachLifecycleOwner(value: Any) {
        if (owner !== value) return
        owner = null
        deactivate()
    }
    private fun deactivate() {
        invalidateObservation()
        change { it.copy(active = false, loading = true, readFailure = null, editor = null) }
    }
    fun retry(value: Any, revision: Long) {
        val current = state.value
        if (isLifecycleOwnerActive(value) && current.revision == revision &&
            !current.saving && current.readFailure != null) observe()
    }
    fun open(value: Any, revision: Long) {
        val current = state.value
        if (!isLifecycleOwnerActive(value) || current.revision != revision ||
            !current.known || current.saving || current.editor != null) return
        change { it.copy(writeFailed = false, editor = DailyReadingGoalDraft(UUID.randomUUID().toString(),
            enabled = current.savedMinutes != null, minutesText = (current.savedMinutes ?: 15).toString())) }
    }
    fun editEnabled(value: Any, requestId: String, revision: Long, enabled: Boolean) {
        val draft = editable(value, requestId, revision) ?: return
        if (draft.enabled == enabled) return
        change { it.copy(writeFailed = false, editor = draft.copy(enabled = enabled, revision = draft.revision + 1)) }
    }
    fun editMinutes(value: Any, requestId: String, revision: Long, text: String) {
        val draft = editable(value, requestId, revision) ?: return
        if (draft.minutesText == text) return
        change { it.copy(writeFailed = false, editor = draft.copy(minutesText = text, revision = draft.revision + 1)) }
    }
    fun dismiss(value: Any, requestId: String, revision: Long) {
        if (!isLifecycleOwnerActive(value) || state.value.saving) return
        val draft = state.value.editor ?: return
        if (draft.requestId == requestId && draft.revision == revision) change { it.copy(editor = null, writeFailed = false) }
    }
    private fun editable(value: Any, requestId: String, revision: Long): DailyReadingGoalDraft? {
        val current = state.value
        return current.editor?.takeIf { isLifecycleOwnerActive(value) && current.known &&
            !current.saving && it.requestId == requestId && it.revision == revision }
    }
    fun save(value: Any, requestId: String, revision: Long) {
        val draft = editable(value, requestId, revision)?.takeIf { it.valid } ?: return
        val minutes = if (draft.enabled) checkNotNull(draft.minutes) else null
        validateDailyReadingGoal(minutes)
        invalidateObservation()
        change { it.copy(saving = true, writeFailed = false) }
        // Once admitted, an atomic DataStore write may finish across a lifecycle handoff.
        // Completion can close only its own draft; a replacement owner never inherits it.
        viewModelScope.launch {
            try {
                repository.saveDailyMinutes(minutes)
                change { current -> current.copy(savedMinutes = minutes, saving = false,
                    editor = current.editor?.takeUnless { it.requestId == requestId }, writeFailed = false) }
            } catch (cancelled: CancellationException) {
                change { it.copy(saving = false) }
                if (state.value.active) observe()
                throw cancelled
            } catch (_: Exception) {
                change { it.copy(saving = false, writeFailed = true) }
            }
            if (state.value.active) observe()
        }
    }
    private fun invalidateObservation() {
        observationGeneration++
        observation?.cancel()
        observation = null
    }
    private fun observe() {
        invalidateObservation()
        val request = observationGeneration
        change { it.copy(loading = true, readFailure = null) }
        if (!state.value.active || state.value.saving) return
        observation = viewModelScope.launch {
            try {
                var received = false
                repository.dailyMinutes.collect { minutes ->
                    if (request != observationGeneration || !state.value.active || state.value.saving) return@collect
                    validateDailyReadingGoal(minutes)
                    received = true
                    change { it.copy(savedMinutes = minutes, loading = false, readFailure = null) }
                }
                check(received) { "No persisted goal observation" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (request == observationGeneration && state.value.active) change {
                    it.copy(loading = false, readFailure = if (failure is DailyReadingGoalException)
                        DailyReadingGoalReadFailure.INVALID_VALUE else DailyReadingGoalReadFailure.SOURCE, editor = null)
                }
            }
        }
    }
    private inline fun change(transform: (DailyReadingGoalState) -> DailyReadingGoalState) {
        val previous = mutable.value
        mutable.value = transform(previous).copy(revision = previous.revision + 1)
    }
}

internal fun parseDailyReadingGoalMinutes(text: String): Int? {
    val value = text.trim()
    if (value.isEmpty() || value.length > 4 || value.any { it !in '0'..'9' }) return null
    return value.toIntOrNull()?.takeIf { it in MIN_DAILY_READING_GOAL_MINUTES..MAX_DAILY_READING_GOAL_MINUTES }
}

internal data class DailyReadingGoalProgress(val targetMillis: Long, val remainingMillis: Long,
    val percent: Int, val achieved: Boolean)

internal fun dailyReadingGoalProgress(minutes: Int, todayMillis: Long): DailyReadingGoalProgress {
    validateDailyReadingGoal(minutes)
    require(todayMillis >= 0)
    val target = minutes.toLong() * 60_000L
    val bounded = minOf(todayMillis, target)
    return DailyReadingGoalProgress(target, (target - todayMillis).coerceAtLeast(0),
        (bounded * 100L / target).toInt(), todayMillis >= target)
}
