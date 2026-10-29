package com.yaprak.reader.feature.librarywords

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yaprak.reader.R
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.core.StartupState
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.ui.theme.YaprakTheme

/** Read-only local source; restore recovery gates composition and cancels its previous owner. */
class LibraryWordsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as YaprakApplication).container
        val factory = viewModelFactory { initializer { LibraryWordsViewModel(container.libraryWords, createSavedStateHandle()) } }
        setContent { YaprakTheme {
            val startup by container.startup.state.collectAsStateWithLifecycle()
            if (startup == StartupState.Ready) LibraryWordsRoute(viewModel(factory = factory),
                onOpen = { startActivity(ReaderActivity.intent(this@LibraryWordsActivity, it)) }, onBack = ::finish,
                onReview = { startActivity(com.yaprak.reader.feature.wordreview.WordReviewActivity.intent(this@LibraryWordsActivity)) })
            else Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(32.dp)) {
                    if (startup == StartupState.Loading) CircularProgressIndicator()
                    Text(stringResource(if (startup == StartupState.Loading) R.string.library_words_loading else R.string.library_words_failed))
                    if (startup == StartupState.Failed) TextButton(container.startup::retry, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.retry), Modifier.fillMaxWidth())
                    }
                }
            }
        } }
    }
    companion object { fun intent(context: Context) = Intent(context, LibraryWordsActivity::class.java) }
}

@Composable
internal fun LibraryWordsRoute(model: LibraryWordsViewModel, onOpen: (String) -> Unit, onBack: () -> Unit, onReview: () -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = remember(model, lifecycle) { Any() }
    DisposableEffect(model, lifecycle) {
        model.attach(owner)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.active(owner, true)
            if (event == Lifecycle.Event.ON_PAUSE) model.active(owner, false)
        }
        lifecycle.addObserver(observer)
        model.active(owner, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); model.detach(owner) }
    }
    LibraryWordsScreen(state,
        onSearch = { if (model.isActive(owner)) model.search(it) },
        onReload = { if (model.isActive(owner)) model.reload(it) },
        onPractice = { revision, command -> if (model.isActive(owner)) model.practice(revision, command) },
        onOpen = { revision, entry -> if (model.isActive(owner)) model.open(revision, entry) { id ->
            check(model.isActive(owner) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            onOpen(id)
        } }, onBack = onBack, onReview = { if (model.isActive(owner)) onReview() })
}
