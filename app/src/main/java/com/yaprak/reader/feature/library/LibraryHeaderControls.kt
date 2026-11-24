package com.yaprak.reader.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R

/** "Kitaplık" title with the search, filter/view and tools buttons. */
@Composable
internal fun LibraryTitleRow(
    hasBooks: Boolean,
    filterActive: Boolean,
    onToggleSearch: () -> Unit,
    onToggleFilters: () -> Unit,
    onTools: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Kitaplık", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        if (hasBooks) IconButton(onToggleSearch) {
            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_books))
        }
        if (hasBooks) IconButton(onToggleFilters) {
            androidx.compose.material3.BadgedBox(badge = {
                if (filterActive) androidx.compose.material3.Badge()
            }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Filtreler ve görünüm") }
        }
        IconButton(onTools) { Icon(Icons.Default.MoreVert, contentDescription = "Kitaplık araçları") }
    }
}

/** Optional search field, optional filter/view panel, and the count + sort line. */
@Composable
internal fun LibrarySearchControls(
    screen: LibraryScreenState,
    library: LibraryState.Ready,
    shownCount: Int,
    searchOpen: Boolean,
    filtersExpanded: Boolean,
    preferencesEnabled: Boolean,
    browse: LibraryBrowseActions,
    onOpenFilterDialog: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val query = screen.query
    val filter = screen.filter
    val organizationFilters = screen.organizationFilters
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (searchOpen || query.isNotEmpty()) OutlinedTextField(value = query, onValueChange = browse.onSearch,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(stringResource(R.string.search_books)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) ({
                IconButton(onClick = { browse.onSearch(""); focus.clearFocus() }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                }
            }) else null,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
        if (filtersExpanded) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryFilters(filter, browse.onFilter)
                TextButton(onClick = onOpenFilterDialog) {
                    Text(if (organizationFilters.isActive)
                        stringResource(R.string.library_filters_active, organizationFilters.activeCount)
                        else stringResource(R.string.library_filters))
                }
                LibraryLayoutPicker(screen.layout, browse.onLayout, preferencesEnabled)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (library.query.isBlank() && filter == LibraryFilter.ALL && !organizationFilters.isActive)
                stringResource(R.string.library_count, library.totalCount)
                else stringResource(R.string.search_count, shownCount, library.totalCount), style = MaterialTheme.typography.labelSmall)
            LibrarySortMenu(screen.sort, browse.onSort, preferencesEnabled)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LibraryFilters(filter: LibraryFilter, onFilter: (LibraryFilter) -> Unit) {
    val labels = mapOf(
        LibraryFilter.ALL to stringResource(R.string.filter_all),
        LibraryFilter.EPUB to stringResource(R.string.filter_epub),
        LibraryFilter.PDF to stringResource(R.string.filter_pdf),
        LibraryFilter.TXT to stringResource(R.string.filter_txt),
        LibraryFilter.CBZ to stringResource(R.string.filter_cbz),
        LibraryFilter.CBR to "CBR",
        LibraryFilter.FB2 to "FB2",
        LibraryFilter.DOCX to "DOCX",
        LibraryFilter.NO_POSITION to stringResource(R.string.filter_no_position),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LibraryFilter.entries.forEach { option ->
            val label = labels.getValue(option)
            val accessibility = stringResource(R.string.filter_accessibility, label)
            FilterChip(selected = filter == option, onClick = { onFilter(option) },
                label = { Text(label) },
                modifier = Modifier.semantics { contentDescription = accessibility })
        }
    }
    if (filter == LibraryFilter.DOCX) Text(stringResource(R.string.docx_format_scope),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LibraryLayoutPicker(
    layout: LibraryLayout,
    onLayout: (LibraryLayout) -> Unit,
    enabled: Boolean,
) {
    val labels = mapOf(
        LibraryLayout.GRID to stringResource(R.string.library_layout_grid),
        LibraryLayout.LIST to stringResource(R.string.library_layout_list),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryLayout.entries.forEach { option ->
            val label = labels.getValue(option)
            val accessibility = stringResource(R.string.library_layout_accessibility, label)
            FilterChip(selected = layout == option, onClick = { onLayout(option) },
                enabled = enabled,
                label = { Text(label) },
                modifier = Modifier.semantics { contentDescription = accessibility })
        }
    }
}

/** View/sort preference problems belong on the main screen, not hidden in the tools dialog. */
@Composable
internal fun LibraryPreferencesFeedback(
    status: LibraryPreferencesStatus,
    onRetry: () -> Unit,
) {
    if (status == LibraryPreferencesStatus.Loading) {
        Text(stringResource(R.string.library_preferences_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val retryAccessibility = stringResource(R.string.library_preferences_retry_accessibility)
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (status == LibraryPreferencesStatus.LoadFailed)
                    R.string.library_preferences_load_error else R.string.library_preferences_save_error))
                TextButton(onClick = onRetry,
                    modifier = Modifier.semantics { contentDescription = retryAccessibility }) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun LibrarySortMenu(sort: LibrarySort, onSort: (LibrarySort) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    val labels = mapOf(
        LibrarySort.RECENT to stringResource(R.string.sort_recent),
        LibrarySort.LAST_READ to stringResource(R.string.sort_last_read),
        LibrarySort.TITLE to stringResource(R.string.sort_title),
        LibrarySort.AUTHOR to stringResource(R.string.sort_author),
    )
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(stringResource(R.string.sort_button, labels.getValue(sort)))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            LibrarySort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labels.getValue(option)) },
                    onClick = { expanded = false; onSort(option) },
                    enabled = enabled,
                    modifier = Modifier.semantics { selected = option == sort },
                )
            }
        }
    }
}
