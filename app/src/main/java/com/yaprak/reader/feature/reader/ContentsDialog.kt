package com.yaprak.reader.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.domain.reader.BookContents
import kotlinx.coroutines.launch

@Composable
internal fun ContentsDialog(contents: BookContents, onSelect: (String) -> Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text("İçindekiler") },
        text = { ContentsDialogBody(contents, onSelect, onDismiss) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContentsDialogBody(contents: BookContents, onSelect: (String) -> Boolean, onDismiss: () -> Unit) {
    var rejected by remember(contents) { mutableStateOf(false) }
    var query by remember(contents) { mutableStateOf("") }
    var collapsedIds by remember(contents) { mutableStateOf(emptySet<String>()) }
    val branches = remember(contents.entries) { contentsBranchIds(contents.entries) }
    val searching = query.isNotBlank()
    val visibleEntries = remember(contents.entries, query, collapsedIds) {
        if (searching) searchContents(contents.entries, query)
        else visibleContentsForFolds(contents.entries, collapsedIds)
    }
    val listState = remember(contents) { LazyListState() }
    val scope = rememberCoroutineScope()
    // One bounded scroll surface keeps search, fold controls and long errors accessible.
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("contents-list"), state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (rejected) item(key = "contents-header-error") {
            Text("Bu başlığa geçilemedi. Yeniden deneyebilir veya başka bir başlık seçebilirsin.",
                color = MaterialTheme.colorScheme.error)
        }
        if (contents.truncated) item(key = "contents-header-truncated") {
            Text("Liste çok uzun; ilk 5.000 başlık gösteriliyor.", style = MaterialTheme.typography.bodySmall)
        }
        if (contents.entries.isEmpty()) item(key = "contents-header-empty") {
            Text("Bu kitap içindekiler listesi sunmuyor. Okuma ekranında sayfalar arasında ilerleyebilirsin.")
        } else {
            item(key = "contents-header-search") {
                OutlinedTextField(value = query,
                    onValueChange = { query = limitContentsQuery(it); rejected = false },
                    label = { Text("İçindekilerde ara") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("contents-search"))
            }
            if (query.isNotEmpty()) item(key = "contents-header-clear") {
                TextButton(onClick = { query = ""; rejected = false }) { Text("Temizle") }
            }
            if (searching) item(key = "contents-header-matches") {
                Text("${visibleEntries.size} / ${contents.entries.size} başlık",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!searching && branches.isNotEmpty()) item(key = "contents-header-folds") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { collapsedIds = branches }, enabled = collapsedIds != branches) {
                        Text("Tümünü daralt")
                    }
                    TextButton(onClick = { collapsedIds = emptySet() }, enabled = collapsedIds.isNotEmpty()) {
                        Text("Tümünü aç")
                    }
                }
            }
            if (visibleEntries.isEmpty()) item(key = "contents-header-no-matches") {
                Text("Eşleşen başlık bulunamadı.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } else items(visibleEntries, key = { "entry:${it.id}" }) { entry ->
                Row(Modifier.fillMaxWidth().padding(start = (entry.depth.coerceIn(0, 4) * 12).dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).testTag("contents-entry-${entry.id}")
                        .clickable(enabled = entry.navigable, role = Role.Button, onClickLabel = "Başlığa git") {
                            if (onSelect(entry.id)) onDismiss() else {
                                rejected = true
                                scope.launch { listState.scrollToItem(0) }
                            }
                        }.padding(top = 14.dp, end = 8.dp, bottom = 14.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.bodyLarge,
                            color = if (entry.navigable) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!entry.navigable) Text("Bu başlığa gidilemiyor", style = MaterialTheme.typography.labelSmall)
                    }
                    if (!searching && entry.id in branches) {
                        val collapsed = entry.id in collapsedIds
                        IconButton(onClick = {
                            collapsedIds = if (collapsed) collapsedIds - entry.id else collapsedIds + entry.id
                        }, modifier = Modifier.testTag("contents-fold-${entry.id}").semantics {
                            contentDescription = "${entry.title}: alt başlıkları ${if (collapsed) "aç" else "daralt"}"
                            stateDescription = if (collapsed) "Daraltılmış" else "Açık"
                        }) {
                            Icon(if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight
                                else Icons.Default.KeyboardArrowDown, contentDescription = null)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Bound dialog state without cutting a UTF-16 surrogate pair. */
private fun limitContentsQuery(value: String): String {
    if (value.length <= 200) return value
    val limit = if (Character.isHighSurrogate(value[199]) && Character.isLowSurrogate(value[200])) 199 else 200
    return value.take(limit)
}
