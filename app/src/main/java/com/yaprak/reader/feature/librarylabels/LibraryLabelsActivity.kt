package com.yaprak.reader.feature.librarylabels

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.core.StartupState
import com.yaprak.reader.ui.theme.YaprakTheme

class LibraryLabelsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val container = (application as YaprakApplication).container
        val factory = viewModelFactory { initializer { LibraryLabelsViewModel(container.libraryLabels) } }
        setContent { YaprakTheme {
            val startup by container.startup.state.collectAsStateWithLifecycle()
            when (startup) {
                StartupState.Ready -> LibraryLabelsRoute(viewModel(factory = factory), ::finish)
                else -> Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
                    Text(if (startup == StartupState.Loading) "Kitaplık hazırlanıyor…" else "Kitaplık kurtarılamadı.")
                    if (startup == StartupState.Failed) Button(container.startup::retry) { Text("Yeniden dene") }
                    TextButton(::finish) { Text("Kütüphaneye dön") }
                } }
            }
        } }
    }
    companion object { fun intent(context: Context) = Intent(context, LibraryLabelsActivity::class.java) }
}
