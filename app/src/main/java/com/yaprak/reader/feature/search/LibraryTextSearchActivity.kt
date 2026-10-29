package com.yaprak.reader.feature.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

/** Internal entry point. Library recovery and live lifecycle gate every index and chapter action. */
class LibraryTextSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as YaprakApplication).container
        val factory = viewModelFactory { initializer {
            LibraryTextSearchViewModel(container.libraryTextIndex, createSavedStateHandle())
        } }
        setContent {
            YaprakTheme {
                val startup by container.startup.state.collectAsStateWithLifecycle()
                when (startup) {
                    StartupState.Ready -> LibraryTextSearchRoute(viewModel(factory = factory),
                        onOpenChapter = { hit -> startActivity(ReaderActivity.intent(this@LibraryTextSearchActivity,
                            hit.target)) }, onBack = ::finish,
                        onAssistant = { scope, query -> startActivity(com.yaprak.reader.feature.assistant.BookAssistantActivity.intent(
                            this@LibraryTextSearchActivity, scope, query)) })
                    StartupState.Loading, StartupState.Failed -> Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            if (startup == StartupState.Loading) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.library_text_startup_loading), Modifier.padding(top = 20.dp))
                            } else {
                                Text(stringResource(R.string.library_text_startup_failed), color = MaterialTheme.colorScheme.error)
                                Button(onClick = container.startup::retry, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                                    Text(stringResource(R.string.library_text_retry), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object { fun intent(context: Context) = Intent(context, LibraryTextSearchActivity::class.java) }
}
