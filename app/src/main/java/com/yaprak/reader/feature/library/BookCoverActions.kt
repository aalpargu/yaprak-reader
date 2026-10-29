package com.yaprak.reader.feature.library

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.CoverEditFailure
import com.yaprak.reader.domain.library.CoverEditResult

@Composable
internal fun BookCoverActions(
    book: Book,
    state: CoverEditState,
    enabled: Boolean,
    onChange: () -> Unit,
    onReset: (() -> Unit)?,
    coverLoader: (suspend (Book) -> Bitmap?)? = null,
) {
    val currentState = state.takeIf { it.targets(book) } ?: CoverEditState.Idle
    val actionsEnabled = enabled && currentState !is CoverEditState.Selecting &&
        currentState !is CoverEditState.Changing && currentState !is CoverEditState.Resetting
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.custom_cover_title), style = MaterialTheme.typography.labelLarge)
        LibraryBookCover(book, Modifier.fillMaxWidth().height(160.dp), compact = true, coverLoader = coverLoader)
        TextButton(onClick = onChange, enabled = actionsEnabled) { Text(stringResource(R.string.custom_cover_change)) }
        if (book.customCover != null && onReset != null) {
            TextButton(onClick = onReset, enabled = actionsEnabled) { Text(stringResource(R.string.custom_cover_reset)) }
        }
        Text(stringResource(R.string.custom_cover_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val message = when (currentState) {
            is CoverEditState.Selecting -> stringResource(R.string.custom_cover_selecting)
            is CoverEditState.Changing -> stringResource(R.string.custom_cover_changing)
            is CoverEditState.Resetting -> stringResource(R.string.custom_cover_resetting)
            is CoverEditState.Finished -> currentState.result.message()
            CoverEditState.Idle -> null
        }
        if (message != null) {
            val failed = currentState is CoverEditState.Finished && currentState.result !is CoverEditResult.Changed &&
                currentState.result !is CoverEditResult.Reset
            Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (currentState is CoverEditState.Changing || currentState is CoverEditState.Resetting)
            LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

private fun CoverEditState.targets(book: Book): Boolean = when (this) {
    CoverEditState.Idle -> false
    is CoverEditState.Selecting -> bookId == book.id && contentSha256 == book.sha256
    is CoverEditState.Changing -> bookId == book.id && contentSha256 == book.sha256
    is CoverEditState.Resetting -> bookId == book.id && contentSha256 == book.sha256
    is CoverEditState.Finished -> bookId == book.id && contentSha256 == book.sha256
}

@Composable
private fun CoverEditResult.message(): String {
    val resource = when (this) {
        is CoverEditResult.Changed -> R.string.custom_cover_changed
        is CoverEditResult.Reset -> R.string.custom_cover_resetted
        CoverEditResult.Missing -> R.string.custom_cover_missing
        CoverEditResult.Stale -> R.string.custom_cover_stale
        is CoverEditResult.Failed -> when (reason) {
            CoverEditFailure.INVALID_IMAGE -> R.string.custom_cover_invalid
            CoverEditFailure.TOO_LARGE -> R.string.custom_cover_too_large
            CoverEditFailure.ACCESS -> R.string.custom_cover_access
            CoverEditFailure.IO -> R.string.custom_cover_io
        }
    }
    val privateFileMayRemain = (this as? CoverEditResult.Changed)?.privateFileMayRemain == true ||
        (this as? CoverEditResult.Reset)?.privateFileMayRemain == true
    return stringResource(resource) + if (privateFileMayRemain)
        " " + stringResource(R.string.custom_cover_cleanup_warning) else ""
}
