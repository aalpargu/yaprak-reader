package com.yaprak.reader.feature.library

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book

/** "Kaldığın yerden devam et": the most recently read book, with its cover and progress. */
@Composable
internal fun LibraryContinueCard(
    book: Book,
    percent: Int?,
    enabled: Boolean,
    onClick: () -> Unit,
    coverLoader: (suspend (Book) -> Bitmap?)?,
) {
    Surface(onClick = onClick, enabled = enabled,
        color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryBookCover(book, Modifier.size(52.dp, 72.dp), compact = true, coverLoader = coverLoader)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.continue_reading_title), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .75f))
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (percent != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = { percent / 100f }, Modifier.weight(1f).height(4.dp),
                        drawStopIndicator = {})
                    Text("  %$percent", style = MaterialTheme.typography.labelMedium)
                }
            }
            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.continue_reading_action),
                tint = MaterialTheme.colorScheme.primary)
        }
    }
}
