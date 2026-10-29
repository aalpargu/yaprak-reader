package com.yaprak.reader.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.StudyExportFormat

/** No default selection: each tap explicitly chooses the document's format. */
@Composable
internal fun StudyExportFormatDialog(book: Book, enabled: Boolean, changed: Boolean,
    onSelect: (StudyExportFormat) -> Unit, onDismiss: () -> Unit) {
    val liveEnabled by rememberUpdatedState(enabled)
    val liveSelect by rememberUpdatedState(onSelect)
    val liveDismiss by rememberUpdatedState(onDismiss)
    Dialog(onDismissRequest = { liveDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .testTag("study-export-formats"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.study_export_format_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall)
                    Text(book.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.export_study_formats_body), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                    if (changed) Text(stringResource(R.string.study_export_error_changed_book),
                        color = MaterialTheme.colorScheme.error)
                    StudyExportFormat.entries.forEach { format -> key(book, format) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { if (liveEnabled) liveSelect(format) }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().testTag("study-export-format-${format.name}")) {
                                Text(stringResource(format.labelResource()), Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                            }
                            Text(stringResource(format.descriptionResource()), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                        }
                    } }
                }
                TextButton(onClick = { liveDismiss() }, modifier = Modifier.fillMaxWidth()
                    .testTag("study-export-format-cancel")) {
                    Text(stringResource(R.string.study_export_format_cancel), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun StudyExportFormat.labelResource(): Int = when (this) {
    StudyExportFormat.TEXT -> R.string.study_export_format_text
    StudyExportFormat.MARKDOWN -> R.string.study_export_format_markdown
    StudyExportFormat.ANKI_TSV -> R.string.study_export_format_anki
    StudyExportFormat.PDF -> R.string.study_export_format_pdf
    StudyExportFormat.ANKI_STUDY_TSV -> R.string.study_export_format_anki_study
    StudyExportFormat.NOTION_CSV -> R.string.study_export_format_notion
    StudyExportFormat.OBSIDIAN_ZIP -> R.string.study_export_format_obsidian
}

private fun StudyExportFormat.descriptionResource(): Int = when (this) {
    StudyExportFormat.TEXT -> R.string.study_export_format_text_help
    StudyExportFormat.MARKDOWN -> R.string.study_export_format_markdown_help
    StudyExportFormat.ANKI_TSV -> R.string.study_export_format_anki_help
    StudyExportFormat.PDF -> R.string.study_export_format_pdf_help
    StudyExportFormat.ANKI_STUDY_TSV -> R.string.study_export_format_anki_study_help
    StudyExportFormat.NOTION_CSV -> R.string.study_export_format_notion_help
    StudyExportFormat.OBSIDIAN_ZIP -> R.string.study_export_format_obsidian_help
}
