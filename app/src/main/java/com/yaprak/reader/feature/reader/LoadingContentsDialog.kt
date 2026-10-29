package com.yaprak.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R
import com.yaprak.reader.reader.ContentsLoadFailure

@Composable
internal fun LoadingContentsDialog(state: ContentsState, onSelect: (ContentsState, String) -> Boolean,
    onRetry: (ContentsState) -> Unit, onClose: (ContentsRequest) -> Unit) {
    val request = state.request ?: return
    key(request) {
        val live by rememberUpdatedState(state)
        AlertDialog(modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { onClose(request) }, title = { Text("İçindekiler") },
            text = { ContentsLoadBody(state, { expected, id -> live === expected && onSelect(expected, id) },
                { expected -> if (live === expected) onRetry(expected) }, { onClose(request) }) },
            confirmButton = { TextButton({ onClose(request) }) { Text("Kapat") } })
    }
}

@Composable
internal fun ContentsLoadBody(state: ContentsState, onSelect: (ContentsState, String) -> Boolean,
    onRetry: (ContentsState) -> Unit, onClose: () -> Unit) {
    when {
        state.loading -> Text(stringResource(R.string.contents_loading))
        state.failure != null -> Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(when (state.failure) {
                ContentsLoadFailure.LIMIT -> R.string.contents_limit
                ContentsLoadFailure.UNSUPPORTED -> R.string.contents_unsupported
                ContentsLoadFailure.SOURCE -> R.string.contents_failed
            }))
            TextButton({ onRetry(state) }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.contents_retry))
            }
        }
        state.contents != null -> ContentsDialogBody(state.contents,
            { id -> onSelect(state, id) }, onClose)
    }
}
