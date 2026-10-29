package com.yaprak.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yaprak.reader.feature.library.LibraryRoute
import com.yaprak.reader.feature.library.LibraryViewModel
import com.yaprak.reader.feature.librarynotes.LibraryNotesActivity
import com.yaprak.reader.core.StartupState
import com.yaprak.reader.ui.theme.YaprakTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as YaprakApplication).container
        val factory = viewModelFactory { initializer {
            LibraryViewModel(container.library, container.importer, createSavedStateHandle(),
                exporter = container.exporter, remover = container.remover, backup = container.backup,
                coverLoader = container.covers, libraryPreferences = container.libraryPreferences,
                studyExporter = container.studyExporter, coverEditor = container.coverEditor,
                discovery = container.discovery, bookFolders = container.bookFolders)
        } }
        setContent {
            YaprakTheme {
                val startup by container.startup.state.collectAsStateWithLifecycle()
                when (startup) {
                    StartupState.Ready -> LibraryRoute(viewModel(factory = factory),
                        onAllNotes = { startActivity(LibraryNotesActivity.intent(this)) },
                        onTextSearch = { startActivity(com.yaprak.reader.feature.search.LibraryTextSearchActivity.intent(this)) },
                        onStatistics = { startActivity(com.yaprak.reader.feature.statistics.ReadingStatisticsActivity.intent(this)) },
                        onWords = { startActivity(com.yaprak.reader.feature.librarywords.LibraryWordsActivity.intent(this)) },
                        onLabels = { startActivity(com.yaprak.reader.feature.librarylabels.LibraryLabelsActivity.intent(this)) })
                    StartupState.Loading, StartupState.Failed -> Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            if (startup == StartupState.Loading) {
                                CircularProgressIndicator()
                                Text("Kitaplık hazırlanıyor…", Modifier.padding(top = 20.dp))
                            } else {
                                Text("Yarım kalan geri yükleme kurtarılamadı. Verilerinizi korumak için kitaplık açılmadı.",
                                    color = MaterialTheme.colorScheme.error)
                                Button(onClick = container.startup::retry, Modifier.padding(top = 20.dp)) {
                                    Text("Yeniden dene")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
