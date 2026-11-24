package com.yaprak.reader.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R

/**
 * The library. Owns only which window is open (book details, filters, study format, tools) and
 * the search/filter panels' visibility; the parts live in [LibraryShelf], [LibraryDialogs] and
 * [LibraryToolsDialog]. Any running work ([LibraryWorkState.mutationBusy]) closes the details window
 * and keeps book actions waiting.
 */
@Composable
fun LibraryScreen(
    screen: LibraryScreenState,
    work: LibraryWorkState,
    snackbarHostState: SnackbarHostState,
    browse: LibraryBrowseActions,
    books: LibraryBookActions = LibraryBookActions(),
    tools: LibraryToolActions = LibraryToolActions(),
    discovery: LibraryDiscoveryActions = LibraryDiscoveryActions(),
) {
    val state = screen.library
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var filterDialogOpen by remember { mutableStateOf(false) }
    var studyFormatSelection by remember { mutableStateOf<StudyFormatSelection?>(null) }
    SideEffect {
        if (work.bookFolder.available) discovery.onFolderOpeningUi(state !is LibraryState.Loading,
            state is LibraryState.Ready && !work.mutationBusy && selectedId == null && !filterDialogOpen &&
                studyFormatSelection == null && work.importNotice?.isBatch != true)
    }
    val resetFilters = {
        if (browse.onResetFilters != null) browse.onResetFilters.invoke() else {
            browse.onFilter(LibraryFilter.ALL)
            browse.onOrganizationFilters(LibraryOrganizationFilters())
            browse.onSearch("")
        }
    }
    LaunchedEffect(work.removalNotice?.id) {
        if (work.removalNotice != null) selectedId = null
    }
    LaunchedEffect(work.backupBusy) { if (work.backupBusy) selectedId = null }
    LaunchedEffect(work.importBusy) { if (work.importBusy) selectedId = null }
    LaunchedEffect(work.discoveryBusy) {
        if (work.discoveryBusy) { selectedId = null; filterDialogOpen = false }
    }
    LibraryDialogs(screen, work, snackbarHostState, browse, books, discovery,
        selectedId = selectedId, onSelect = { selectedId = it },
        filterDialogOpen = filterDialogOpen, onFilterDialog = { filterDialogOpen = it },
        studyFormatSelection = studyFormatSelection, onStudyFormatSelection = { studyFormatSelection = it },
        resetFilters = resetFilters)
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var libraryToolsOpen by rememberSaveable { mutableStateOf(false) }
    if (libraryToolsOpen) LibraryToolsDialog(state, work, tools, discovery, selectedId, filterDialogOpen,
        studyFormatOpen = studyFormatSelection != null, onClose = { libraryToolsOpen = false })
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state is LibraryState.Ready && state.totalCount > 0 && !work.mutationBusy) {
                ExtendedFloatingActionButton(onClick = browse.onImport, icon = { AddIcon() }, text = { Text(stringResource(R.string.add_book)) })
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LibraryShelf(screen, work, browse,
                searchOpen = searchOpen, onSearchOpen = { searchOpen = it },
                filtersExpanded = filtersExpanded, onFiltersExpanded = { filtersExpanded = it },
                onTools = { libraryToolsOpen = true }, onOpenFilterDialog = { filterDialogOpen = true },
                onDetails = { selectedId = it }, resetFilters = resetFilters,
                modifier = Modifier.widthIn(max = 900.dp).fillMaxSize())
        }
    }
}
