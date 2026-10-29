package com.yaprak.reader.feature.librarynotes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.StudyExportResult
import com.yaprak.reader.domain.notes.LibraryNotesExportFormat
import com.yaprak.reader.domain.notes.MAX_LIBRARY_NOTES_EXPORT_RECORDS
import com.yaprak.reader.feature.library.message

@Composable
internal fun LibraryNotesExportFormatDialog(choice: LibraryNotesExportChoice, current: Boolean,
    onSelect: (Long, LibraryNotesExportFormat) -> Unit, onDismiss: (Long) -> Unit) {
    val liveCurrent by rememberUpdatedState(current)
    val liveSelect by rememberUpdatedState(onSelect)
    val liveDismiss by rememberUpdatedState(onDismiss)
    val allowed = current && choice.totalCount <= MAX_LIBRARY_NOTES_EXPORT_RECORDS
    LibraryNotesExportModal(onDismiss = { liveDismiss(choice.id) },
        footer = {
            TextButton(onClick = { liveDismiss(choice.id) }, modifier = Modifier.fillMaxWidth().testTag("library-notes-export-cancel")) {
                Text(stringResource(R.string.study_export_format_cancel), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }) {
        Text(stringResource(R.string.library_notes_export_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall)
        ExportScope(choice.query)
        Text(stringResource(R.string.library_notes_export_count, choice.totalCount), Modifier.fillMaxWidth())
        Text(stringResource(R.string.library_notes_export_help), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        if (!current) Text(stringResource(R.string.library_notes_export_stale), color = MaterialTheme.colorScheme.error)
        if (choice.totalCount > MAX_LIBRARY_NOTES_EXPORT_RECORDS)
            Text(stringResource(R.string.library_notes_export_limit), color = MaterialTheme.colorScheme.error)
        LibraryNotesExportFormat.entries.forEach { format ->
            TextButton(enabled = allowed, onClick = {
                if (liveCurrent && choice.totalCount <= MAX_LIBRARY_NOTES_EXPORT_RECORDS) liveSelect(choice.id, format)
            }, modifier = Modifier.fillMaxWidth().testTag("library-notes-export-format-${format.name}")) {
                Text(stringResource(when (format) {
                    LibraryNotesExportFormat.TEXT -> R.string.library_notes_export_text
                    LibraryNotesExportFormat.MARKDOWN -> R.string.library_notes_export_markdown
                    LibraryNotesExportFormat.PDF -> R.string.library_notes_export_pdf
                    LibraryNotesExportFormat.NOTION_CSV -> R.string.library_notes_export_notion_csv
                    LibraryNotesExportFormat.ANKI_TSV -> R.string.library_notes_export_anki
                    LibraryNotesExportFormat.OBSIDIAN_ZIP -> R.string.library_notes_export_obsidian
                }), Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
            }
            if (format == LibraryNotesExportFormat.PDF)
                Text(stringResource(R.string.library_notes_export_pdf_help), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            if (format == LibraryNotesExportFormat.ANKI_TSV)
                Text(stringResource(R.string.library_notes_export_anki_help), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            if (format == LibraryNotesExportFormat.NOTION_CSV)
                Text(stringResource(R.string.library_notes_export_notion_help), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            if (format == LibraryNotesExportFormat.OBSIDIAN_ZIP)
                Text(stringResource(R.string.library_notes_export_obsidian_help), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun LibraryNotesExportResultDialog(notice: LibraryNotesExportNotice, onClose: (Long) -> Unit) {
    val liveClose by rememberUpdatedState(onClose)
    val context = LocalContext.current
    LibraryNotesExportModal(onDismiss = { liveClose(notice.id) }, footer = {
        TextButton(onClick = { liveClose(notice.id) }, modifier = Modifier.fillMaxWidth().testTag("library-notes-export-result-close")) {
            Text(stringResource(R.string.library_notes_export_close), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }) {
        Text(stringResource(R.string.library_notes_export_result), style = MaterialTheme.typography.headlineSmall)
        ExportScope(notice.query)
        val text = when (val result = notice.result) {
            is StudyExportResult.Exported -> stringResource(R.string.library_notes_export_success, result.noteCount)
            is StudyExportResult.Failed -> result.message(context)
        }
        Text(text, Modifier.fillMaxWidth().testTag("library-notes-export-result"))
    }
}

@Composable
private fun ExportScope(query: String) {
    Text(if (query.isEmpty()) stringResource(R.string.library_notes_export_all)
        else stringResource(R.string.library_notes_export_query, query), Modifier.fillMaxWidth())
}

@Composable
private fun LibraryNotesExportModal(onDismiss: () -> Unit, footer: @Composable () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
                footer()
            }
        }
    }
}
