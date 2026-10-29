package com.yaprak.reader.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookReadingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class OrganizationFilterChoices(val authors: List<String> = emptyList(),
    val series: List<String> = emptyList(), val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(), val collections: List<String> = emptyList())

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun LibraryOrganizationFiltersDialog(books: List<Book>, filters: LibraryOrganizationFilters,
    onApply: (LibraryOrganizationFilters) -> Unit, onDismiss: () -> Unit, onResetAll: () -> Unit) {
    var draft by remember { mutableStateOf(filters) }
    val liveApply by rememberUpdatedState(onApply)
    val liveDismiss by rememberUpdatedState(onDismiss)
    val liveReset by rememberUpdatedState(onResetAll)
    val choices by produceState(OrganizationFilterChoices(), books) {
        value = withContext(Dispatchers.Default) {
            fun unique(values: List<String?>) = values.filterNotNull().filter(String::isNotBlank)
                .distinctBy { it.searchKey() }.sortedBy { it.searchKey() }
            OrganizationFilterChoices(unique(books.map { it.authors }), unique(books.map { it.organization.series }),
                unique(books.map { it.organization.genre }), unique(books.flatMap { it.organization.tags }),
                unique(books.flatMap { it.organization.collections }))
        }
    }
    Dialog(onDismissRequest = { liveDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.library_filters), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.organization_filter_help), style = MaterialTheme.typography.bodySmall)
                FilterValuePicker(stringResource(R.string.organization_author), draft.author, choices.authors) {
                    draft = draft.copy(author = it)
                }
                FilterValuePicker(stringResource(R.string.organization_series), draft.series, choices.series) {
                    draft = draft.copy(series = it)
                }
                FilterValuePicker(stringResource(R.string.organization_genre), draft.genre, choices.genres) {
                    draft = draft.copy(genre = it)
                }
                FilterValuePicker(stringResource(R.string.organization_tag), draft.tag, choices.tags) {
                    draft = draft.copy(tag = it)
                }
                FilterValuePicker(stringResource(R.string.organization_collection), draft.collection, choices.collections) {
                    draft = draft.copy(collection = it)
                }
                Text(stringResource(R.string.organization_reading_status), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = draft.readingStatus == null,
                        onClick = { draft = draft.copy(readingStatus = null) },
                        label = { Text(stringResource(R.string.filter_all)) })
                    BookReadingStatus.entries.forEach { status ->
                        FilterChip(selected = draft.readingStatus == status,
                            onClick = { draft = draft.copy(readingStatus = status) },
                            label = { Text(readingStatusLabel(status)) })
                    }
                }
                Row(Modifier.fillMaxWidth().toggleable(value = draft.favoritesOnly, role = Role.Checkbox,
                    onValueChange = { draft = draft.copy(favoritesOnly = it) }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draft.favoritesOnly, onCheckedChange = null)
                    Text(stringResource(R.string.organization_favorites_only))
                }
                TextButton(onClick = { liveReset() }) { Text(stringResource(R.string.organization_reset_filters)) }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { liveDismiss() }) { Text(stringResource(R.string.cancel_remove)) }
                    TextButton(onClick = { liveApply(draft) }) { Text(stringResource(R.string.organization_apply_filters)) }
                }
            }
        }
    }
}

@Composable
private fun FilterValuePicker(label: String, selected: String?, choices: List<String>, onChoose: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val liveChoose by rememberUpdatedState(onChoose)
    val liveExpanded by rememberUpdatedState(expanded)
    val matching by produceState(choices, choices, query) {
        value = withContext(Dispatchers.Default) {
            val key = query.searchKey().trim()
            if (key.isEmpty()) choices else choices.filter { it.searchKey().contains(key) }
        }
    }
    val accessibility = stringResource(R.string.organization_filter_field, label)
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { query = ""; expanded = true },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = accessibility }) {
            Text(selected ?: stringResource(R.string.filter_all))
        }
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp),
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(label, style = MaterialTheme.typography.headlineSmall) }
                item {
                    OutlinedTextField(query, onValueChange = { query = it.take(200) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.organization_search_choices)) })
                }
                item {
                    TextButton(onClick = { if (expanded && liveExpanded) { expanded = false; liveChoose(null) } },
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.filter_all)) }
                }
                items(matching, key = { it }) { value ->
                    TextButton(onClick = { if (expanded && liveExpanded) { expanded = false; liveChoose(value) } },
                        modifier = Modifier.fillMaxWidth()) { Text(value) }
                }
                if (matching.isEmpty()) item { Text(stringResource(R.string.organization_no_choices)) }
                item { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.close)) } }
            }
        }
    }
}
