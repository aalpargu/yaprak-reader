package com.yaprak.reader.feature.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yaprak.reader.R

@Composable
internal fun ComicBookViewPreferenceMessage(state: ComicBookViewPreferencesState) {
    val message = when (state.status) {
        ComicBookViewStatus.LOADING -> R.string.cbz_spread_loading
        ComicBookViewStatus.SAVING -> R.string.cbz_spread_saving
        ComicBookViewStatus.APPLYING -> R.string.cbz_spread_applying
        ComicBookViewStatus.LOAD_FAILED -> R.string.cbz_spread_load_failed
        ComicBookViewStatus.SAVE_FAILED -> R.string.cbz_spread_save_failed
        ComicBookViewStatus.APPLY_FAILED -> R.string.cbz_spread_apply_failed
        else -> null
    } ?: return
    Text(stringResource(message), Modifier.fillMaxWidth(),
        color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
