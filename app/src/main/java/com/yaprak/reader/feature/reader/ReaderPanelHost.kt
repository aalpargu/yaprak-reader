package com.yaprak.reader.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag

/** Only the admitted top entry is composed, so suspended editors cannot leave an input Window. */
@Composable
internal fun ReaderPanelHost(state: ReaderPresentationState, kind: ReaderPanelKind, content: @Composable () -> Unit) {
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val known = androidx.compose.runtime.remember { mutableSetOf<Long>() }
    val entry = state.activePanel?.takeIf { it.kind == kind }
    androidx.compose.runtime.SideEffect {
        val live = state.panels.filter { it.kind == kind }.map { it.id }.toSet()
        known.filterNot { it in live }.forEach(saved::removeState)
        known.retainAll(live)
        entry?.let { known += it.id }
    }
    entry?.let { key(it.id) { saved.SaveableStateProvider(it.id) { content() } } }
}

/** A returning Window must actually own native focus before a one-shot Compose focus request. */
internal fun Modifier.readerReturnFocusTarget(controller: ReaderPresentationController, focusKey: String): Modifier = composed {
    val requester = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val state by controller.state.collectAsState()
    val view = androidx.compose.ui.platform.LocalView.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val effect = state.returnFocus?.takeIf { it.key == focusKey }
    androidx.compose.runtime.LaunchedEffect(effect) {
        effect ?: return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        awaitReaderPanelFocus(view)
        keyboard?.hide()
        while (view.isAttachedToWindow && androidx.core.view.ViewCompat.getRootWindowInsets(view)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
            androidx.compose.runtime.withFrameNanos { }
        if (view.isAttachedToWindow && view.hasWindowFocus() &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            controller.isReturnFocusCurrent(effect)) {
            if (requester.requestFocus(FocusDirection.Enter)) controller.acknowledgeReturnFocus(effect)
        }
    }
    // Compose Buttons use SystemDefined focusability, which refuses requests in touch mode.
    // Override only eligibility; the Button still removes its focus target when disabled.
    this.focusRequester(requester).focusProperties { canFocus = true }.testTag(focusKey)
}

/** A route's own child editor can return to a stable record button without keeping a second Window. */
internal fun Modifier.readerDialogReturnFocusTarget(requested: Boolean, onRestored: () -> Unit): Modifier = composed {
    val requester = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val latestRequested by androidx.compose.runtime.rememberUpdatedState(requested)
    val consumed by androidx.compose.runtime.rememberUpdatedState(onRestored)
    val view = androidx.compose.ui.platform.LocalView.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && latestRequested) consumed()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.LaunchedEffect(requested) {
        if (!requested) return@LaunchedEffect
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) { consumed(); return@LaunchedEffect }
        androidx.compose.runtime.withFrameNanos { }
        awaitReaderPanelFocus(view)
        keyboard?.hide()
        while (view.isAttachedToWindow && androidx.core.view.ViewCompat.getRootWindowInsets(view)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
            androidx.compose.runtime.withFrameNanos { }
        if (latestRequested && view.isAttachedToWindow && view.hasWindowFocus() &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            if (requester.requestFocus(FocusDirection.Enter)) consumed()
        }
    }
    this.focusRequester(requester).focusProperties { canFocus = true }
}
