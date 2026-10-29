package com.yaprak.reader.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yaprak.reader.domain.search.LibraryTextHit

@Composable
internal fun LibraryTextSearchRoute(model: LibraryTextSearchViewModel,
    onOpenChapter: (LibraryTextHit) -> Unit, onBack: () -> Unit,
    onAssistant: (com.yaprak.reader.domain.search.BookTextScope, String) -> Unit = { _, _ -> }) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = remember(model, lifecycle) { Any() }
    fun mayAct() = model.isLifecycleOwnerActive(owner) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
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
    val rebuildScope = state.scope
    val rebuildRevision = state.revision
    LibraryTextSearchScreen(state,
        onQueryChange = { if (mayAct()) model.changeQuery(it) },
        onSubmit = { query, revision -> if (mayAct()) model.submit(query, revision) },
        onRetry = { if (mayAct()) model.retry(it) },
        onRebuild = { if (mayAct()) model.rebuild(it, rebuildScope, rebuildRevision) },
        onCancelBuild = { if (mayAct()) model.cancelBuild(it) },
        onScopeChange = { scope, revision -> if (mayAct()) model.changeScope(scope, revision) },
        onAssistant = { scope, query, revision ->
            val current = model.state.value
            if (mayAct() && current.actionsEnabled && current.revision == revision && current.scope == scope &&
                current.result?.books?.singleOrNull { it.book.id == scope.bookId && it.book.sha256 == scope.contentSha256 }
                    ?.book?.format?.let { com.yaprak.reader.domain.search.supportsBookSourceRetrieval(it) } == true &&
                current.searchedQuery == query && current.result?.hits?.isNotEmpty() == true) onAssistant(scope, query)
        },
        onOpen = { hit -> if (mayAct()) model.open(hit, state.revision) { resolved ->
            check(mayAct()) { "Text search screen is paused" }
            onOpenChapter(resolved)
        } }, onBack = { if (mayAct()) onBack() })
}
