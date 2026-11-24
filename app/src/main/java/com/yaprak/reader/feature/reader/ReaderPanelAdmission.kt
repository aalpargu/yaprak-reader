package com.yaprak.reader.feature.reader

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private class PanelCompositionLease {
    var active = true
    var epoch by mutableLongStateOf(0L)
}

/** A disposed or pre-pause button callback cannot edit a retained model in a later presentation. */
@Composable
internal fun rememberReaderPanelAdmission(childKey: String? = null, canMutate: () -> Boolean): () -> Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lease = remember(lifecycle, childKey) { PanelCompositionLease() }
    val latestAllowed by rememberUpdatedState(canMutate)
    DisposableEffect(lease, lifecycle) {
        lease.active = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) lease.epoch++
        }
        lifecycle.addObserver(observer)
        onDispose { lease.active = false; lifecycle.removeObserver(observer) }
    }
    val renderedEpoch = lease.epoch
    return {
        lease.active && lease.epoch == renderedEpoch &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && latestAllowed()
    }
}
