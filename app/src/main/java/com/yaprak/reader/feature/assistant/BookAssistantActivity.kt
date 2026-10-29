package com.yaprak.reader.feature.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.yaprak.reader.domain.search.BookTextScope
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.ui.theme.YaprakTheme

class BookAssistantActivity : ComponentActivity() {
    private var indexLaunchPending = false
    override fun onResume() { indexLaunchPending = false; super.onResume() }
    private fun openIndex() {
        if (indexLaunchPending) return
        indexLaunchPending = true
        try { startActivity(com.yaprak.reader.feature.search.LibraryTextSearchActivity.intent(this)) }
        catch (_: android.content.ActivityNotFoundException) { failedIndexLaunch() }
        catch (_: SecurityException) { failedIndexLaunch() }
    }
    private fun failedIndexLaunch() {
        indexLaunchPending = false
        android.widget.Toast.makeText(this, R.string.assistant_index_open_failed, android.widget.Toast.LENGTH_SHORT).show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = BookTextScope(intent.getStringExtra(BOOK).orEmpty(), intent.getStringExtra(HASH).orEmpty())
        if (!scope.isValid) { finish(); return }
        enableEdgeToEdge()
        val container = (application as YaprakApplication).container
        val factory = viewModelFactory { initializer { BookAssistantViewModel(scope, intent.getStringExtra(KEYWORDS).orEmpty(),
            container.bookSources, container.libraryTextIndex, container.bookAnswers) } }
        setContent { YaprakTheme {
            val startup by container.startup.state.collectAsStateWithLifecycle()
            if (startup == StartupState.Ready) {
                val model: BookAssistantViewModel = viewModel(factory = factory)
                BookAssistantRoute(model, onOpen = { startActivity(ReaderActivity.intent(this, it.target)) }, onBack = ::finish, onOpenIndex = ::openIndex)
            } else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
                Text(stringResource(R.string.assistant_library_unavailable))
                if (startup == StartupState.Failed) TextButton(onClick = container.startup::retry) { Text(stringResource(R.string.library_text_retry)) }
                TextButton(onClick = ::finish) { Text(stringResource(R.string.assistant_back)) }
            }
        } }
    }
    companion object {
        private const val BOOK = "assistant.book"
        private const val HASH = "assistant.hash"
        private const val KEYWORDS = "assistant.keywords"
        fun intent(context: Context, scope: BookTextScope, keywords: String) = Intent(context, BookAssistantActivity::class.java)
            .putExtra(BOOK, scope.bookId).putExtra(HASH, scope.contentSha256).putExtra(KEYWORDS, keywords)
    }
}

@Composable internal fun BookAssistantRoute(model: BookAssistantViewModel,
    onOpen: (com.yaprak.reader.domain.search.LibraryTextHit) -> Unit, onBack: () -> Unit, onOpenIndex: (() -> Unit)? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = remember(model, lifecycle) { Any() }
    fun mayAct() = model.isActive(owner) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    DisposableEffect(model, lifecycle) {
        model.attachOwner(owner)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> model.setOwnerActive(owner, true)
                Lifecycle.Event.ON_PAUSE -> model.setOwnerActive(owner, false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        model.setOwnerActive(owner, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); model.detachOwner(owner) }
    }
    BookAssistantScreen(state,
        onKeywords = { if (mayAct()) model.editKeywords(it) }, onQuestion = { if (mayAct()) model.editQuestion(it) },
        onEndpoint = { if (mayAct()) model.editEndpoint(it) }, onToken = { if (mayAct()) model.editToken(it) },
        onPrepare = { if (mayAct()) model.prepare(it) }, onSend = { if (mayAct()) model.send(it) },
        onCancel = { if (mayAct()) model.cancel(it) }, onOpen = { citation, revision ->
            if (mayAct()) model.open(citation, revision) { hit -> check(mayAct()); onOpen(hit) }
        }, onBack = { if (mayAct()) onBack() },
        onMode = { mode, revision -> if (mayAct()) model.chooseMode(mode, revision) },
        onLoadSections = { if (mayAct()) model.loadSections(it) },
        onSection = { section, revision -> if (mayAct()) model.chooseSection(section, revision) },
        onStudy = { task, revision -> if (mayAct()) model.study(task, revision) },
        onLoadChapters = { if (mayAct()) model.loadChapters(it) },
        onChapter = { chapter, revision -> if (mayAct()) model.chooseChapter(chapter, revision) },
        onPrepareIndex = { revision -> if (mayAct()) model.prepareIndex(revision) },
        onOpenIndex = onOpenIndex?.let { action -> { revision: Long ->
            val current = model.state.value
            if (mayAct() && current.revision == revision && !current.busy && current.book != null &&
                current.indexState == com.yaprak.reader.domain.search.LibraryTextBookState.NOT_INDEXED) action()
        } })
}
