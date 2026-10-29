package com.yaprak.reader.feature.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R
import com.yaprak.reader.domain.settings.BookPdfViewPreferencesFailure
import com.yaprak.reader.domain.settings.PdfFitPreference

@Composable
internal fun PdfBookViewPreferenceMessage(state: PdfBookViewPreferencesState) {
    val message = when {
        state.failure == BookPdfViewPreferencesFailure.STALE_BOOK -> R.string.pdf_book_view_stale
        state.failure == BookPdfViewPreferencesFailure.INVALID_VALUE -> R.string.pdf_book_view_invalid
        state.status == PdfBookViewStatus.LOADING -> R.string.pdf_book_view_loading
        state.status == PdfBookViewStatus.SAVING -> R.string.pdf_book_view_saving
        state.status == PdfBookViewStatus.LOAD_FAILED -> R.string.pdf_book_view_load_error
        state.status == PdfBookViewStatus.SAVE_FAILED -> R.string.pdf_book_view_save_error
        state.status == PdfBookViewStatus.APPLY_FAILED -> R.string.pdf_book_view_apply_error
        else -> null
    }
    message?.let {
        Text(stringResource(it), Modifier.fillMaxWidth().padding(12.dp),
            color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall)
    }
    if (state.status == PdfBookViewStatus.READY) {
        val applied = state.applied ?: return
        Text(stringResource(R.string.pdf_book_view_saved, applied.horizontalPercent, applied.verticalPercent,
            stringResource(if (applied.fitMode == PdfFitPreference.WIDTH) R.string.pdf_book_view_fit_width
                else R.string.pdf_book_view_fit_page)), Modifier.fillMaxWidth().padding(12.dp),
            style = MaterialTheme.typography.bodySmall)
    }
}
