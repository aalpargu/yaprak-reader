package com.yaprak.reader.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookOrganization
import com.yaprak.reader.domain.library.BookReadingStatus

internal fun organizationFromDraft(status: BookReadingStatus, favorite: Boolean,
    series: String, genre: String, tags: String, collections: String): BookOrganization? = try {
    BookOrganization(status, favorite, series, genre, tags.split(','), collections.split(',')).normalized()
} catch (_: IllegalArgumentException) { null }

@Composable
internal fun readingStatusLabel(status: BookReadingStatus): String = stringResource(when (status) {
    BookReadingStatus.UNSET -> R.string.reading_status_unset
    BookReadingStatus.TO_READ -> R.string.reading_status_to_read
    BookReadingStatus.READING -> R.string.reading_status_reading
    BookReadingStatus.COMPLETED -> R.string.reading_status_completed
})

/** Only this dialog owns its draft; canceling it never invokes the save callback. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun BookOrganizationDialog(book: Book, saving: Boolean, failed: Boolean,
    enabled: Boolean, onSave: (BookOrganization) -> Unit, onDismiss: () -> Unit) {
    var statusName by rememberSaveable(book.id, book.sha256) { mutableStateOf(book.organization.readingStatus.name) }
    var favorite by rememberSaveable(book.id, book.sha256) { mutableStateOf(book.organization.favorite) }
    var series by rememberSaveable(book.id, book.sha256) { mutableStateOf(book.organization.series.orEmpty()) }
    var genre by rememberSaveable(book.id, book.sha256) { mutableStateOf(book.organization.genre.orEmpty()) }
    var tags by rememberSaveable(book.id, book.sha256) { mutableStateOf(book.organization.tags.joinToString(", ")) }
    var collections by rememberSaveable(book.id, book.sha256) { mutableStateOf(book.organization.collections.joinToString(", ")) }
    val initialOrganization = remember(book.id, book.sha256) { book.organization }
    val status = BookReadingStatus.entries.firstOrNull { it.name == statusName } ?: BookReadingStatus.UNSET
    val draft = organizationFromDraft(status, favorite, series, genre, tags, collections)
    val changed = initialOrganization != book.organization
    val liveEnabled by rememberUpdatedState(enabled && !saving && !changed)
    val liveSave by rememberUpdatedState(onSave)
    val liveDismiss by rememberUpdatedState(onDismiss)
    val liveIdentity by rememberUpdatedState(book.id to book.sha256)
    val liveDraft by rememberUpdatedState(draft)
    val liveSaving by rememberUpdatedState(saving)
    val renderedIdentity = book.id to book.sha256
    val canEdit = enabled && !saving && !changed
    Dialog(onDismissRequest = { if (!liveSaving) liveDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.organize_book), style = MaterialTheme.typography.headlineSmall)
                Text(book.title, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.organization_reading_status), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BookReadingStatus.entries.forEach { option ->
                        FilterChip(selected = status == option, onClick = { statusName = option.name },
                            enabled = canEdit, label = { Text(readingStatusLabel(option)) })
                    }
                }
                Row(Modifier.fillMaxWidth().toggleable(value = favorite, enabled = canEdit,
                    role = Role.Checkbox, onValueChange = { favorite = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = favorite, onCheckedChange = null, enabled = canEdit)
                    Text(stringResource(R.string.organization_favorite))
                }
                OutlinedTextField(value = series, onValueChange = { series = it.take(BookOrganization.MAX_TEXT_LENGTH + 1) },
                    singleLine = true, enabled = canEdit, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.organization_series)) })
                OutlinedTextField(value = genre, onValueChange = { genre = it.take(BookOrganization.MAX_TEXT_LENGTH + 1) },
                    singleLine = true, enabled = canEdit, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.organization_genre)) })
                OutlinedTextField(value = tags, onValueChange = { tags = it.take(10_000) },
                    minLines = 2, maxLines = 4, enabled = canEdit, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.organization_tags)) })
                OutlinedTextField(value = collections, onValueChange = { collections = it.take(10_000) },
                    minLines = 2, maxLines = 4, enabled = canEdit, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.organization_collections)) })
                Text(stringResource(R.string.organization_limits, BookOrganization.MAX_TEXT_LENGTH,
                    BookOrganization.MAX_LABEL_COUNT, BookOrganization.MAX_LABEL_LENGTH),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (draft == null) Text(stringResource(R.string.organization_invalid),
                    color = MaterialTheme.colorScheme.error)
                if (saving) CircularProgressIndicator()
                if (failed) Text(stringResource(R.string.organization_save_failed),
                    color = MaterialTheme.colorScheme.error)
                if (changed && !saving) Text(stringResource(R.string.organization_changed_book),
                    color = MaterialTheme.colorScheme.error)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { if (!liveSaving) liveDismiss() }, enabled = !saving) {
                        Text(stringResource(R.string.cancel_remove))
                    }
                    TextButton(onClick = {
                        val currentStatus = BookReadingStatus.entries.firstOrNull { it.name == statusName }
                            ?: BookReadingStatus.UNSET
                        val currentDraft = organizationFromDraft(currentStatus, favorite, series, genre, tags, collections)
                        if (liveEnabled && liveIdentity == renderedIdentity && liveDraft == draft &&
                            currentDraft == draft && draft != null)
                            liveSave(draft)
                    }, enabled = canEdit && draft != null) { Text(stringResource(R.string.organization_save)) }
                }
            }
        }
    }
}
