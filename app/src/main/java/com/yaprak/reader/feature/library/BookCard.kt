package com.yaprak.reader.feature.library

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookReadingStatus

/** Tap opens the book; long press or the ⋮ button opens its details. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookCard(
    book: Book,
    onClick: () -> Unit,
    compact: Boolean = false,
    coverLoader: (suspend (Book) -> Bitmap?)? = null,
    onDetails: (() -> Unit)? = null,
    progressPercent: Int? = null,
) {
    val author = book.authors ?: stringResource(R.string.unknown_author)
    val status = if (book.organization.readingStatus == BookReadingStatus.UNSET) null
        else readingStatusLabel(book.organization.readingStatus)
    val favorite = if (book.organization.favorite) stringResource(R.string.organization_favorite) else null
    val organization = listOfNotNull(status, favorite).joinToString(" · ")
    val progress = progressPercent?.takeIf { it in 0..100 }
    val accessibilityName = listOfNotNull(book.title, author, book.format.name, organization.ifBlank { null },
        progress?.let { "yüzde $it okundu" }).joinToString(", ")
    val detailsLabel = "${book.title} ayrıntıları"
    Card(
        modifier = (if (compact) Modifier.fillMaxWidth() else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onDetails, onLongClickLabel = detailsLabel)
            .semantics {
                contentDescription = accessibilityName
                if (onDetails != null) customActions = listOf(CustomAccessibilityAction(detailsLabel) { onDetails(); true })
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        if (compact) {
            Row(Modifier.fillMaxWidth().heightIn(min = 96.dp), verticalAlignment = Alignment.CenterVertically) {
                LibraryBookCover(book, Modifier.size(72.dp, 96.dp).clearAndSetSemantics {},
                    compact = true, coverLoader = coverLoader)
                Column(Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(author, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(book.format.name, organization.ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    BookProgress(progress)
                }
                DetailsButton(onDetails, detailsLabel)
            }
        } else {
            Box {
                LibraryBookCover(book, Modifier.fillMaxWidth().height(188.dp), coverLoader = coverLoader)
            }
            Column(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, minLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(author, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DetailsButton(onDetails, detailsLabel)
                }
                if (organization.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(organization, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Box(Modifier.padding(end = 14.dp, top = 6.dp)) { BookProgress(progress) }
            }
        }
    }
}

@Composable
private fun BookProgress(percent: Int?) {
    if (percent == null || percent == 0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(progress = { percent / 100f }, Modifier.weight(1f).height(3.dp), drawStopIndicator = {})
        Text("  %$percent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailsButton(onDetails: (() -> Unit)?, label: String) {
    if (onDetails == null) return
    IconButton(onDetails, Modifier.size(40.dp)) {
        Icon(Icons.Default.MoreVert, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
