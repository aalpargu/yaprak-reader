package com.yaprak.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun ReaderBrightnessGestureIndicator(percent: Int) {
    Box(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Surface(Modifier.widthIn(max = 280.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Text("Parlaklık: %$percent", Modifier.padding(12.dp).testTag("brightness-gesture-percent"))
        }
    }
}
