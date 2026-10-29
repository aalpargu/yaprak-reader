package com.yaprak.reader.feature.library

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookOrganization
import com.yaprak.reader.domain.library.BookRemovalResult
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BookDetailsDialog(book: Book, onRead: () -> Unit, onExport: () -> Unit,
    exportEnabled: Boolean, onDismiss: () -> Unit,
    removeEnabled: Boolean = true, removalState: BookRemovalState = BookRemovalState.Idle,
    onRemove: () -> Unit = {}, onClearRemovalError: () -> Unit = {},
    metadataEdit: BookMetadataEditState = BookMetadataEditState.Idle,
    editEnabled: Boolean = true,
    onSaveMetadata: (Book, String, String?) -> Unit = { _, _, _ -> },
    onClearMetadataState: (String) -> Unit = {},
    onStudyExport: (() -> Unit)? = null,
    readEnabled: Boolean = true,
    organizationEdit: BookOrganizationEditState = BookOrganizationEditState.Idle,
    onSaveOrganization: (Book, BookOrganization) -> Unit = { _, _ -> },
    onClearOrganizationState: (String, String) -> Unit = { _, _ -> },
    coverLoader: (suspend (Book) -> Bitmap?)? = null,
    coverEdit: CoverEditState = CoverEditState.Idle,
    coverEditEnabled: Boolean = true,
    onChangeCover: (() -> Unit)? = null,
    onResetCover: (() -> Unit)? = null,
    studyExportFormatsAvailable: Boolean = false,
) {
    var confirmRemoval by rememberSaveable(book.id) { mutableStateOf(false) }
    var editing by rememberSaveable(book.id) { mutableStateOf(false) }
    var titleDraft by rememberSaveable(book.id) { mutableStateOf(book.title) }
    var authorsDraft by rememberSaveable(book.id) { mutableStateOf(book.authors.orEmpty()) }
    var submitted by remember(book.id) { mutableStateOf(false) }
    var organizing by rememberSaveable(book.id, book.sha256) { mutableStateOf(false) }
    val liveOrganizing by rememberUpdatedState(organizing)
    val liveBook by rememberUpdatedState(book)
    val liveEditEnabled by rememberUpdatedState(editEnabled)
    val liveCoverEnabled by rememberUpdatedState(coverEditEnabled)
    val coverBusy = coverEdit is CoverEditState.Selecting || coverEdit is CoverEditState.Changing ||
        coverEdit is CoverEditState.Resetting
    val liveCoverBusy by rememberUpdatedState(coverBusy)
    val coverCallbackAllowed = {
        liveCoverEnabled && !liveCoverBusy && liveBook.id == book.id && liveBook.sha256 == book.sha256 &&
            liveBook.customCover?.id == book.customCover?.id
    }
    val removing = removalState is BookRemovalState.Removing && removalState.bookId == book.id
    val removalError = (removalState as? BookRemovalState.Error)?.takeIf { it.bookId == book.id }?.result
    val metadataSaving = metadataEdit is BookMetadataEditState.Saving && metadataEdit.bookId == book.id
    val organizationSaving = organizationEdit is BookOrganizationEditState.Saving &&
        organizationEdit.bookId == book.id && organizationEdit.sha256 == book.sha256
    val saving = metadataSaving || organizationSaving
    val failed = metadataEdit is BookMetadataEditState.Failed && metadataEdit.bookId == book.id
    val normalizedTitle = titleDraft.trim()
    val normalizedAuthors = authorsDraft.trim().ifBlank { null }
    val titleValid = normalizedTitle.length in 1..1000
    val authorsValid = normalizedAuthors == null || normalizedAuthors.length <= 2000
    LaunchedEffect(metadataEdit, submitted) {
        when (metadataEdit) {
            BookMetadataEditState.Idle -> submitted = false
            is BookMetadataEditState.Failed -> if (metadataEdit.bookId == book.id) submitted = false
            is BookMetadataEditState.Saved -> if (metadataEdit.bookId == book.id && editing) {
                editing = false
                submitted = false
                onClearMetadataState(book.id)
            }
            else -> Unit
        }
    }
    LaunchedEffect(organizationEdit) {
        if (organizationEdit is BookOrganizationEditState.Saved &&
            organizationEdit.bookId == book.id && organizationEdit.sha256 == book.sha256 && organizing) {
            organizing = false
            onClearOrganizationState(book.id, book.sha256)
        }
    }
    if (organizing) BookOrganizationDialog(book, saving = organizationSaving,
        failed = organizationEdit is BookOrganizationEditState.Failed &&
            organizationEdit.bookId == book.id && organizationEdit.sha256 == book.sha256,
        enabled = editEnabled || organizationSaving,
        onSave = { organization ->
            if (organizing && liveOrganizing && liveEditEnabled && liveBook.id == book.id && liveBook.sha256 == book.sha256)
                onSaveOrganization(book, organization)
        },
        onDismiss = {
            organizing = false
            onClearOrganizationState(book.id, book.sha256)
        })
    if (!editing && !organizing) AlertDialog(
        onDismissRequest = { if (!removing && !saving && !coverBusy) onDismiss() },
        title = { Text(stringResource(R.string.book_details)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(book.title, style = MaterialTheme.typography.headlineSmall)
                Text(book.authors ?: stringResource(R.string.unknown_author))
                onChangeCover?.let { change ->
                    // A delayed semantics/touch action belongs to the revision it was rendered for.
                    // Recreate Material's callback holders when that revision changes; the shared
                    // liveBook above still rejects the disposed revision's captured callback.
                    key(book.id, book.sha256, book.customCover?.id) {
                        BookCoverActions(book, coverEdit, coverEditEnabled && !coverBusy,
                            onChange = { if (coverCallbackAllowed()) change() },
                            onReset = onResetCover?.let { reset -> { if (coverCallbackAllowed()) reset() } },
                            coverLoader = coverLoader)
                    }
                }
                Detail(stringResource(R.string.organization_reading_status), readingStatusLabel(book.organization.readingStatus))
                if (book.organization.favorite) Text(stringResource(R.string.organization_favorite))
                book.organization.series?.let { Detail(stringResource(R.string.organization_series), it) }
                book.organization.genre?.let { Detail(stringResource(R.string.organization_genre), it) }
                if (book.organization.tags.isNotEmpty())
                    Detail(stringResource(R.string.organization_tag), book.organization.tags.joinToString(", "))
                if (book.organization.collections.isNotEmpty())
                    Detail(stringResource(R.string.organization_collection), book.organization.collections.joinToString(", "))
                Detail(stringResource(R.string.details_filename), book.originalName)
                Detail(stringResource(R.string.details_format), book.format.name)
                Detail(stringResource(R.string.details_size), Formatter.formatFileSize(LocalContext.current, book.byteSize))
                book.pageCount?.let { Detail(stringResource(R.string.details_pages), it.toString()) }
                Detail(stringResource(R.string.details_added), DateFormat.getDateInstance().format(Date(book.importedAt)))
                TextButton(onClick = {
                    onClearOrganizationState(book.id, book.sha256)
                    organizing = true
                }, enabled = editEnabled && !saving && !removing && !coverBusy) {
                    Text(stringResource(R.string.organize_book))
                }
                TextButton(onClick = {
                    titleDraft = book.title
                    authorsDraft = book.authors.orEmpty()
                    submitted = false
                    onClearMetadataState(book.id)
                    editing = true
                }, enabled = editEnabled && !saving && !removing && !coverBusy) {
                    Text("Başlığı ve yazarı düzenle")
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { if (!liveCoverBusy) onExport() }, enabled = exportEnabled && !coverBusy) {
                        Text(stringResource(R.string.export_book))
                    }
                    Text(stringResource(R.string.export_file_only), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                onStudyExport?.let { export ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { if (!liveCoverBusy) export() }, enabled = exportEnabled && !removing && !saving && !coverBusy) {
                            Text(stringResource(R.string.export_study_records))
                        }
                        Text(stringResource(if (studyExportFormatsAvailable) R.string.export_study_formats_body
                            else R.string.export_study_records_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { if (!liveCoverBusy) confirmRemoval = true }, enabled = removeEnabled && !removing && !coverBusy) {
                    Text(stringResource(R.string.remove_book), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (!liveCoverBusy) onRead() }, enabled = readEnabled && !removing && !saving && !coverBusy) { Text("Oku") }
        },
        dismissButton = {
            TextButton(onClick = { if (!liveCoverBusy) onDismiss() }, enabled = !removing && !saving && !coverBusy) { Text(stringResource(R.string.close)) }
        },
    )
    if (editing) AlertDialog(
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = {
            if (!saving) { editing = false; submitted = false; onClearMetadataState(book.id) }
        },
        title = { Text("Kitap bilgilerini düzenle") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = titleDraft, onValueChange = { titleDraft = it },
                    label = { Text("Başlık") }, singleLine = true, enabled = !saving,
                    isError = !titleValid, modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Kitap başlığı alanı"
                    })
                if (!titleValid) Text("Başlık 1–1000 karakter olmalı.", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(value = authorsDraft, onValueChange = { authorsDraft = it },
                    label = { Text("Yazar (isteğe bağlı)") }, enabled = !saving,
                    maxLines = 3, modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Kitap yazarı alanı"
                    }, isError = !authorsValid)
                if (!authorsValid) Text("Yazar en çok 2000 karakter olabilir.", color = MaterialTheme.colorScheme.error)
                if (saving) CircularProgressIndicator()
                if (failed && !saving) Text("Kitap bilgileri kaydedilemedi. Taslağı düzelterek yeniden dene.",
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                submitted = true
                onSaveMetadata(book, normalizedTitle, normalizedAuthors)
            }, enabled = titleValid && authorsValid && !saving && !submitted) {
                Text("Kaydet", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = {
            TextButton(onClick = { editing = false; submitted = false; onClearMetadataState(book.id) },
                enabled = !saving) { Text("Vazgeç") }
        },
    )
    if (confirmRemoval) AlertDialog(
        onDismissRequest = {
            if (!removing) { confirmRemoval = false; onClearRemovalError() }
        },
        title = { Text(stringResource(R.string.remove_book_confirm_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.remove_book_confirm_body, book.title))
                if (removing) CircularProgressIndicator()
                removalError?.let { result ->
                    Text(when (result) {
                        BookRemovalResult.InUse -> stringResource(R.string.remove_error_in_use)
                        BookRemovalResult.Missing -> stringResource(R.string.remove_error_missing)
                        else -> stringResource(R.string.remove_error_io)
                    }, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRemove, enabled = removeEnabled && !removing) {
                Text(stringResource(R.string.remove_book_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmRemoval = false; onClearRemovalError() }, enabled = !removing) {
                Text(stringResource(R.string.cancel_remove))
            }
        },
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
