package com.yaprak.reader.feature.librarynotes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun LibraryNotesRoute(
    model: LibraryNotesViewModel,
    onCopyText: (String) -> Unit,
    onOpenBook: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val request by model.exports.request.collectAsStateWithLifecycle()
    val exporting by model.exports.exporting.collectAsStateWithLifecycle()
    val choice by model.exportChoice.collectAsStateWithLifecycle()
    val notice by model.exports.notice.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = remember(model, lifecycle) { Any() }
    DisposableEffect(model, lifecycle) {
        model.attachLifecycleOwner(owner)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> model.setLifecycleOwnerActive(owner, true)
                Lifecycle.Event.ON_PAUSE -> model.setLifecycleOwnerActive(owner, false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        model.setLifecycleOwnerActive(owner, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); model.detachLifecycleOwner(owner) }
    }
    LibraryNotesScreen(state,
        onSearch = { if (model.isLifecycleOwnerActive(owner)) model.search(it) },
        onReload = { if (model.isLifecycleOwnerActive(owner)) model.reload() },
        onCopy = { note ->
            if (model.isLifecycleOwnerActive(owner) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.copy(note) { text ->
                check(model.isLifecycleOwnerActive(owner) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { "Note screen is paused" }
                onCopyText(text)
            }
        },
        onOpen = { note ->
            if (model.isLifecycleOwnerActive(owner) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.open(note) { id ->
                check(model.isLifecycleOwnerActive(owner) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { "Note screen is paused" }
                onOpenBook(id)
            }
        }, onBack = onBack,
        onExport = if (model.exports.available) { expected ->
            if (model.isLifecycleOwnerActive(owner) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                model.showExportChoices(expected)
        } else null,
        exportBusy = request != null || exporting || choice != null || notice != null)
    choice?.let { selected -> key(selected.id) {
        LibraryNotesExportFormatDialog(selected, model.exportChoiceCurrent(selected),
            onSelect = { id, format ->
                if (model.isLifecycleOwnerActive(owner) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    model.selectExportFormat(id, format)
            }, onDismiss = model::dismissExportChoices)
    } }
    request?.let { pending -> key(pending.requestId) {
        LibraryNotesExportPicker(pending, state.active && model.isLifecycleOwnerActive(owner),
            claim = { id, format ->
                model.isLifecycleOwnerActive(owner) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    model.exports.claimLaunch(id, format)
            }, onResult = model.exports::onResult, onLaunchFailed = model.exports::onLaunchFailed)
    } }
    notice?.let { result -> key(result.id) {
        LibraryNotesExportResultDialog(result, model.exports::acknowledge)
    } }
}
