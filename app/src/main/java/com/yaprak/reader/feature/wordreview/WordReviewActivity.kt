package com.yaprak.reader.feature.wordreview

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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yaprak.reader.R
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.core.StartupState
import com.yaprak.reader.ui.theme.YaprakTheme

class WordReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val container = (application as YaprakApplication).container
        val factory = viewModelFactory { initializer { WordReviewViewModel(container.wordReviews) } }
        setContent { YaprakTheme {
            val startup by container.startup.state.collectAsStateWithLifecycle()
            if (startup == StartupState.Ready) WordReviewRoute(viewModel(factory = factory), ::finish)
            else Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(32.dp)) {
                    if (startup == StartupState.Loading) CircularProgressIndicator()
                    Text(stringResource(if (startup == StartupState.Loading) R.string.library_words_loading else R.string.library_words_failed))
                    if (startup == StartupState.Failed) TextButton(container.startup::retry, Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry)) }
                }
            }
        } }
    }
    companion object { fun intent(context: Context) = Intent(context, WordReviewActivity::class.java) }
}

@Composable
internal fun WordReviewRoute(model: WordReviewViewModel, onBack: () -> Unit) {
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
    WordReviewScreen(state, { revision, command -> if (model.isActive(owner)) model.command(revision, command) },
        { revision, rating -> if (model.isActive(owner)) model.rate(revision, rating) },
        { if (model.isActive(owner)) model.reload(it) }, { if (model.isActive(owner)) model.retry(it) }, onBack)
}
