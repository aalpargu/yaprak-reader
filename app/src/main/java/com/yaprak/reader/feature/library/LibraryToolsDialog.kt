package com.yaprak.reader.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R

/**
 * "Kitaplık araçları": other library screens, folder discovery, the saved folder and backup.
 * Opening another screen re-checks, at click time, that the library is idle and nothing else is open.
 */
@Composable
internal fun LibraryToolsDialog(
    state: LibraryState,
    work: LibraryWorkState,
    tools: LibraryToolActions,
    discovery: LibraryDiscoveryActions,
    selectedId: String?,
    filterDialogOpen: Boolean,
    studyFormatOpen: Boolean,
    onClose: () -> Unit,
) {
    val liveState by rememberUpdatedState(state)
    val liveMutationBusy by rememberUpdatedState(work.mutationBusy)
    val liveSelectedId by rememberUpdatedState(selectedId)
    val liveFilterDialogOpen by rememberUpdatedState(filterDialogOpen)
    val liveStudyOpen by rememberUpdatedState(studyFormatOpen)
    val liveAllNotes by rememberUpdatedState(tools.onAllNotes)
    val liveTextSearch by rememberUpdatedState(tools.onTextSearch)
    val liveStatistics by rememberUpdatedState(tools.onStatistics)
    val liveWords by rememberUpdatedState(tools.onWords)
    val liveLabels by rememberUpdatedState(tools.onLabels)
    val bookFolderState = work.bookFolder
    val idle = state is LibraryState.Ready && !liveMutationBusy && selectedId == null && !filterDialogOpen && !studyFormatOpen
    fun idleNow() = liveState is LibraryState.Ready && !liveMutationBusy &&
        liveSelectedId == null && !liveFilterDialogOpen && !liveStudyOpen
    val management: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit = {
        tools.onLabels?.let { labels -> item(key = "labels", span = { GridItemSpan(maxLineSpan) }) {
            key(labels) {
                LibraryToolRow("Etiket ve koleksiyonlar", "Raflar, etiketler, seriler", Icons.Default.Star, enabled = idle, onClick = {
                    if (liveLabels === labels && idleNow()) run { onClose(); labels() }
                })
            }
        } }
        tools.onWords?.let { words -> item(key = "vocabulary", span = { GridItemSpan(maxLineSpan) }) {
            key(words) {
                LibraryToolRow(stringResource(R.string.library_words_title), "Kaydettiğin kelimeler ve tekrar", Icons.Default.Favorite, enabled = idle, onClick = {
                    if (liveWords === words && idleNow()) run { onClose(); words() }
                })
            }
        } }
        tools.onStatistics?.let { statistics -> item(key = "statistics", span = { GridItemSpan(maxLineSpan) }) {
            key(statistics) {
                LibraryToolRow(stringResource(R.string.reading_statistics_title), "Süre, seri ve hedefler", Icons.Default.DateRange, enabled = idle, onClick = {
                    if (liveStatistics === statistics && idleNow()) run { onClose(); statistics() }
                })
            }
        } }
        tools.onTextSearch?.let { textSearch -> item(key = "textSearch", span = { GridItemSpan(maxLineSpan) }) {
            key(textSearch) {
                LibraryToolRow(stringResource(R.string.library_text_title), "Tüm kitapların içinde ara", Icons.Default.Search, enabled = idle, onClick = {
                    if (liveTextSearch === textSearch && idleNow()) run { onClose(); textSearch() }
                })
            }
        } }
        tools.onAllNotes?.let { allNotes -> item(key = "allNotes", span = { GridItemSpan(maxLineSpan) }) {
            LibraryToolRow(stringResource(R.string.library_notes_title), "Notlar ve vurgular, dışa aktarma", Icons.Default.Edit, enabled = state is LibraryState.Ready && !liveMutationBusy, onClick = { if (liveAllNotes != null && liveState is LibraryState.Ready && !liveMutationBusy) run { onClose(); allNotes() } })
        } }
        discovery.onDiscover?.let { discover -> item(key = "folderDiscovery", span = { GridItemSpan(maxLineSpan) }) {
            LibraryToolRow(stringResource(R.string.discovery_open_folder),
                if (work.discovery is DiscoveryState.Choosing) stringResource(R.string.discovery_selecting)
                else "Bir klasördeki kitapları tara, istediklerini ekle", Icons.Default.Add,
                enabled = state is LibraryState.Ready && !liveMutationBusy,
                onClick = { if (!liveMutationBusy) run { onClose(); discover() } })
        } }
        // A saved folder is created from a scan result; with none saved the panel would only explain that.
        if (bookFolderState.available && (bookFolderState.snapshot?.folder != null || bookFolderState.busy ||
                bookFolderState.failure != null || bookFolderState.feedback != null))
            item(key = "savedFolder", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(horizontal = 20.dp)) {
            BookFolderPanel(bookFolderState, idle, discovery.onSavedFolderScan, discovery.onForgetFolder,
                discovery.onReloadFolder, discovery.onCancelFolderScan, discovery.onFolderAutomatic)
        } }
        if (work.backupAvailable) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val actionsEnabled = state is LibraryState.Ready && !liveMutationBusy
            LibraryBackupPanel(
                createEnabled = actionsEnabled,
                restoreEnabled = actionsEnabled && (state as? LibraryState.Ready)?.totalCount == 0,
                running = work.backupRunning,
                pickerPending = work.backupPickerPending,
                onCreate = tools.onBackupCreate,
                onRestore = tools.onBackupRestore,
            )
        } }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 640.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Kitaplık araçları", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClose) { Icon(Icons.Default.Close, contentDescription = "Kapat") }
                }
                LazyVerticalGrid(columns = GridCells.Fixed(1), verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)) { management() }
            }
        }
    }
}

@Composable
internal fun LibraryToolRow(title: String, supporting: String?, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    com.yaprak.reader.feature.reader.ReaderMenuRow(title, supporting, icon, onClick, enabled = enabled)
}
