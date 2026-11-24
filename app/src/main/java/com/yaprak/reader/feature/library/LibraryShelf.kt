package com.yaprak.reader.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaprak.reader.R

/**
 * The scrolling shelf: title and controls, the continue card, running import/export work, and
 * the books as a grid or a list (or the empty/no-result/loading/error states).
 */
@Composable
internal fun LibraryShelf(
    screen: LibraryScreenState,
    work: LibraryWorkState,
    browse: LibraryBrowseActions,
    searchOpen: Boolean,
    onSearchOpen: (Boolean) -> Unit,
    filtersExpanded: Boolean,
    onFiltersExpanded: (Boolean) -> Unit,
    onTools: () -> Unit,
    onOpenFilterDialog: () -> Unit,
    onDetails: (String) -> Unit,
    resetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = screen.library
    val query = screen.query
    val filter = screen.filter
    val organizationFilters = screen.organizationFilters
    val focus = LocalFocusManager.current
    val liveMutationBusy by rememberUpdatedState(work.mutationBusy)
    val preferencesEnabled = screen.preferenceStatus != LibraryPreferencesStatus.Loading &&
        screen.preferenceStatus != LibraryPreferencesStatus.LoadFailed
    val books = (state as? LibraryState.Ready)?.books.orEmpty()
    val continueBook = if (query.isBlank() && filter == LibraryFilter.ALL && !organizationFilters.isActive)
        mostRecentlyReadBook(books) else null
    LazyVerticalGrid(
        columns = if (screen.layout == LibraryLayout.GRID) GridCells.Adaptive(148.dp) else GridCells.Fixed(1),
        modifier = modifier,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            LibraryTitleRow(hasBooks = state is LibraryState.Ready && state.totalCount > 0,
                filterActive = filter != LibraryFilter.ALL || organizationFilters.isActive,
                onToggleSearch = {
                    val open = !searchOpen
                    onSearchOpen(open)
                    if (!open) { browse.onSearch(""); focus.clearFocus() }
                },
                onToggleFilters = { onFiltersExpanded(!filtersExpanded) },
                onTools = onTools)
        }
        // View/sort preference problems belong on the main screen, not hidden in the tools dialog.
        if (screen.preferenceStatus == LibraryPreferencesStatus.LoadFailed ||
            screen.preferenceStatus == LibraryPreferencesStatus.SaveFailed ||
            (screen.preferenceStatus == LibraryPreferencesStatus.Loading &&
                state is LibraryState.Ready && state.totalCount > 0)) {
            item(key = "libraryPreferencesStatus", span = { GridItemSpan(maxLineSpan) }) {
                LibraryPreferencesFeedback(screen.preferenceStatus, browse.onRetryPreferences)
            }
        }
        continueBook?.let { book ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                val percent = (state as? LibraryState.Ready)?.progress?.get(book.id)?.takeIf { it > 0 }
                LibraryContinueCard(book, percent, enabled = !liveMutationBusy,
                    onClick = { if (!liveMutationBusy) browse.onRead(book.id) }, coverLoader = screen.coverLoader)
            }
        }
        if (work.importing) {
            item(span = { GridItemSpan(maxLineSpan) }) { LibraryImportProgress(work.importProgress, browse.onStopRemainingImports) }
        }
        if (work.exportBusy) {
            item(span = { GridItemSpan(maxLineSpan) }) { LibraryExportProgress(work.studyExporting) }
        }
        when (state) {
            LibraryState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            LibraryState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.library_error))
                    Button(onClick = browse.onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
            is LibraryState.Ready -> {
                if (state.totalCount == 0) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary(liveMutationBusy, browse.onImport) }
                } else {
                    item(key = "librarySearchControls", span = { GridItemSpan(maxLineSpan) }) {
                        LibrarySearchControls(screen, state, books.size, searchOpen, filtersExpanded, preferencesEnabled,
                            browse, onOpenFilterDialog)
                    }
                    if (books.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(if (filter == LibraryFilter.ALL && !organizationFilters.isActive) R.string.no_search_results
                                else R.string.no_filter_results), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(if (filter == LibraryFilter.ALL && !organizationFilters.isActive) R.string.no_search_results_body
                                else R.string.no_filter_results_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (filter != LibraryFilter.ALL || organizationFilters.isActive || query.isNotBlank())
                                TextButton(onClick = resetFilters) { Text(stringResource(R.string.show_all_books)) }
                        }
                    }
                    items(books, key = { it.id }) { book -> BookCard(book,
                        onClick = {
                            if (!liveMutationBusy) browse.onRead(book.id)
                        }, onDetails = { if (!liveMutationBusy) onDetails(book.id) }, compact = screen.layout == LibraryLayout.LIST,
                        coverLoader = screen.coverLoader, progressPercent = state.progress[book.id])
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryImportProgress(importProgress: ImportProgress?, onStopRemainingImports: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.importing), style = MaterialTheme.typography.titleMedium)
            Text(if (importProgress != null && importProgress.total > 1)
                stringResource(R.string.importing_batch_progress,
                    importProgress.completed, importProgress.total)
                else stringResource(R.string.importing_body),
                style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (importProgress != null && importProgress.total > 1 &&
                importProgress.completed < importProgress.total && onStopRemainingImports != null) {
                TextButton(onClick = onStopRemainingImports,
                    enabled = !importProgress.stopRequested) {
                    Text(stringResource(R.string.import_stop_remaining))
                }
                if (importProgress.stopRequested) {
                    Text(stringResource(R.string.import_stop_waiting),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun LibraryExportProgress(studyExporting: Boolean) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (studyExporting) R.string.exporting_study_records
                else R.string.exporting_book), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (studyExporting) R.string.exporting_study_records_body
                else R.string.exporting_book_body), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Suppress("unused") // Former large brand header; kept with its strings until a design decision.
@Composable
private fun LibraryHeader() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary) {
                Icon(painterResource(R.drawable.ic_book), contentDescription = null, modifier = Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.app_name), fontFamily = FontFamily.Serif, fontSize = 23.sp)
                Text(stringResource(R.string.brand_caption), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(36.dp))
        Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.library_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EmptyLibrary(importing: Boolean, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(168.dp, 156.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(112.dp, 138.dp).rotate(-12f).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)))
            Surface(Modifier.size(108.dp, 140.dp).rotate(7f), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), shadowElevation = 3.dp) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_book), contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(30.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onImport, enabled = !importing, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 15.dp)) {
            AddIcon()
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.add_book))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.formats_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(38.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.local_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AddIcon() { Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(22.dp)) }
