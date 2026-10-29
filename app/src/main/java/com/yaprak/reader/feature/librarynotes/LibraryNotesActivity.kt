package com.yaprak.reader.feature.librarynotes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yaprak.reader.R
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.core.StartupState
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.reader.copyPlainSelectedText
import com.yaprak.reader.ui.theme.YaprakTheme

/** Internal, read-only entry point; interrupted restore recovery gates its source and actions. */
class LibraryNotesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as YaprakApplication).container
        val factory = viewModelFactory { initializer {
            LibraryNotesViewModel(container.libraryNotes, createSavedStateHandle(), container.libraryNotesExporter)
        } }
        setContent {
            YaprakTheme {
                val startup by container.startup.state.collectAsStateWithLifecycle()
                when (startup) {
                    StartupState.Ready -> LibraryNotesRoute(viewModel(factory = factory),
                        onCopyText = { copyPlainSelectedText(this@LibraryNotesActivity, it, "Yaprak notu") },
                        onOpenBook = { startActivity(ReaderActivity.intent(this@LibraryNotesActivity, it)) },
                        onBack = ::finish)
                    StartupState.Loading, StartupState.Failed -> Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            if (startup == StartupState.Loading) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.library_notes_startup_loading), Modifier.padding(top = 20.dp))
                            } else {
                                Text(stringResource(R.string.library_notes_startup_failed), color = MaterialTheme.colorScheme.error)
                                Button(onClick = container.startup::retry, Modifier.fillMaxWidth().padding(top = 20.dp)) {
                                    Text(stringResource(R.string.retry), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, LibraryNotesActivity::class.java)
    }
}
