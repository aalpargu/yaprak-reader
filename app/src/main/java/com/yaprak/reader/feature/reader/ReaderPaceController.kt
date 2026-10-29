package com.yaprak.reader.feature.reader

import com.yaprak.reader.reader.ReadingPaceObservation
import com.yaprak.reader.reader.ReadingPaceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class PaceIdentity(val bookId: String, val contentSha256: String)

/** Main-confined. A replaced surface/book/source can never publish into its successor. */
internal class ReaderPaceController(private val scope: CoroutineScope, private val clock: () -> Long) {
    private class Binding(val owner: Any, val identity: PaceIdentity, val source: ReadingPaceSource) {
        val meter = ReadingPaceMeter()
        var active = true
        var permitted = false
        var observation: ReadingPaceObservation? = null
        var job: Job? = null
    }
    private var current: Binding? = null
    private val mutableState = MutableStateFlow<ReadingTimeEstimate?>(null)
    val state = mutableState.asStateFlow()

    fun bind(owner: Any, identity: PaceIdentity, source: ReadingPaceSource?) {
        val old = current
        if (old?.owner === owner && old.identity == identity && old.source === source) return
        detach()
        if (source == null) return
        val binding = Binding(owner, identity, source)
        current = binding; mutableState.value = ReadingTimeEstimate.Measuring
        binding.job = scope.launch {
            coroutineScope {
                launch {
                    source.observations.collect {
                        if (admits(binding)) { binding.observation = it; publish(binding) }
                    }
                }
                launch {
                    while (isActive) {
                        delay(1_000)
                        if (admits(binding)) publish(binding)
                    }
                }
            }
        }
    }

    fun permit(owner: Any?, identity: PaceIdentity?, allowed: Boolean) {
        val binding = current ?: return
        if (binding.owner !== owner || binding.identity != identity) return
        binding.permitted = allowed; publish(binding)
    }

    fun suspend(owner: Any?) {
        val binding = current ?: return
        if (binding.owner !== owner) return
        binding.permitted = false; binding.meter.reset(); binding.observation = null
        mutableState.value = ReadingTimeEstimate.Measuring
    }

    fun detach() {
        current?.let { it.active = false; it.job?.cancel(); it.meter.reset() }
        current = null; mutableState.value = null
    }

    private fun admits(binding: Binding) = binding.active && current === binding
    private fun publish(binding: Binding) {
        if (admits(binding)) mutableState.value = binding.meter.update(binding.observation, binding.permitted, clock())
    }
}
