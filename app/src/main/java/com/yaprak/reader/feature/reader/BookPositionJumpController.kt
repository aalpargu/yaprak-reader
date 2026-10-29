package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.reader.BookPositionNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

internal data class BookPositionJumpState(
    val open: Boolean = false,
    val input: String = "",
    val resolving: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = open && !resolving && bookPercentForInput(input) != null
}

internal fun bookPercentForInput(input: String): Int? {
    val value = input.trim()
    if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
    return value.toIntOrNull()?.takeIf { it in 0..100 }
}

private fun limitBookPercentInput(input: String): String {
    if (input.length <= 32) return input
    val end = if (Character.isHighSurrogate(input[31]) && Character.isLowSurrogate(input[32])) 31 else 32
    // Keep an invalid marker rather than turning an overlong pasted value into a valid target.
    return input.take(end) + "…"
}

/** Activity-owned transient state; all owner calls and final navigation run on Main. */
internal class BookPositionJumpController(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(BookPositionJumpState())
    val state = mutable.asStateFlow()
    private var navigator: BookPositionNavigator? = null
    private var generation = 0L
    private val jobs = mutableListOf<Job>()

    fun open(navigator: BookPositionNavigator, currentPercent: Int?) {
        stopJobs()
        this.navigator = navigator
        mutable.value = BookPositionJumpState(open = true,
            input = currentPercent?.takeIf { it in 0..100 }?.toString().orEmpty())
    }

    fun edit(input: String) {
        if (state.value.open && !state.value.resolving)
            mutable.update { it.copy(input = limitBookPercentInput(input), error = null) }
    }

    fun submit(canNavigate: (BookPositionNavigator) -> Boolean,
        onNavigate: (BookPositionNavigator, ReadingLocation) -> Boolean) {
        val current = state.value
        if (!current.open || current.resolving || !scope.isActive) return
        val percent = bookPercentForInput(current.input)
        if (percent == null) {
            mutable.update { it.copy(error = "0–100 arasında tam sayı gir.") }
            return
        }
        val targetNavigator = navigator ?: return
        val requestGeneration = ++generation
        mutable.update { it.copy(resolving = true, error = null) }
        // Register before starting: an immediate resolver must not escape cancellation bookkeeping.
        val job = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            try {
                currentCoroutineContext().ensureActive()
                if (!canNavigate(targetNavigator)) {
                    showError(requestGeneration)
                    return@launch
                }
                val location = targetNavigator.resolvePercent(percent)
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation || navigator !== targetNavigator || !state.value.open)
                    return@launch
                // The reader may have paused, failed, exited or replaced its surface during resolution.
                if (location == null || !canNavigate(targetNavigator) || !onNavigate(targetNavigator, location)) {
                    showError(requestGeneration)
                } else {
                    generation++
                    navigator = null
                    mutable.value = BookPositionJumpState()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError(requestGeneration)
            } finally {
                if (requestGeneration == generation)
                    mutable.update { it.copy(resolving = false) }
            }
        }
        jobs.removeAll { it.isCompleted }
        jobs += job
        job.start()
    }

    private fun showError(requestGeneration: Long) {
        if (requestGeneration == generation && state.value.open)
            mutable.update { it.copy(error = "Bu kitap konumuna gidilemedi. Yeniden deneyebilirsin.") }
    }

    /** Stops resolution while retaining the user's open draft for another explicit attempt. */
    fun cancel() { stopJobs() }

    fun dismiss() {
        stopJobs()
        navigator = null
        mutable.value = BookPositionJumpState()
    }

    /** Cancelled resolvers remain tracked until finished so the owner can join them before exit. */
    fun stopJobs(): List<Job> {
        generation++
        val unfinished = jobs.filterNot { it.isCompleted }
        unfinished.forEach { it.cancel() }
        jobs.removeAll { it.isCompleted }
        mutable.update { it.copy(resolving = false, error = null) }
        return unfinished
    }
}
