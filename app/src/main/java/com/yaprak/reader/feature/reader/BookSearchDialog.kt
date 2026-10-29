package com.yaprak.reader.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.reader.BookSearchHit
import com.yaprak.reader.reader.BookSearchResults
import com.yaprak.reader.reader.BookSearchKind
import com.yaprak.reader.R

internal sealed interface BookSearchPhase {
    data object Idle : BookSearchPhase
    data object Searching : BookSearchPhase
    data class Ready(val results: BookSearchResults) : BookSearchPhase
    data object Failed : BookSearchPhase
    data object Unsupported : BookSearchPhase
    data object Limit : BookSearchPhase
}

internal data class BookSearchState(
    val query: String = "",
    val phase: BookSearchPhase = BookSearchPhase.Idle,
    val navigationFailed: Boolean = false,
    val selectionDraftTruncated: Boolean = false,
)

/** Search starts only on an explicit button/IME action; typing never scans the book. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookSearchDialog(
    state: BookSearchState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
    onSelect: (BookSearchHit) -> Unit,
    onDismiss: () -> Unit,
    kind: BookSearchKind = BookSearchKind.EPUB,
) {
    val live by rememberUpdatedState(state)
    val lease = remember { SearchDialogLease() }
    DisposableEffect(lease) { onDispose { lease.active = false } }
    fun fresh() = lease.active && live === state
    val searchable = state.query.isNotBlank() && state.phase !is BookSearchPhase.Searching && state.phase !is BookSearchPhase.Unsupported
    Dialog(onDismissRequest = { if (fresh()) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.large) {
            LazyColumn(Modifier.fillMaxSize().testTag("book-search-list"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Kitapta ara", Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall)
                    SearchAction(state, "Kapat", true) { if (fresh()) onDismiss() }
                    if (kind == BookSearchKind.PDF) Text(stringResource(R.string.pdf_search_scope), Modifier.fillMaxWidth())
                }
                item {
                    Text("Aranacak metin", Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { if (fresh()) onQueryChange(it) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("book-search-query"),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (fresh() && searchable) onSearch() }),
                    )
                }
                item {
                    if (state.selectionDraftTruncated) Text(stringResource(R.string.selected_text_book_search_shortened),
                        Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                    SearchAction(state, "Ara", searchable) { if (fresh()) onSearch() }
                    if (state.phase is BookSearchPhase.Searching) SearchAction(state, "Aramayı durdur", true) { if (fresh()) onCancel() }
                }
                if (state.navigationFailed) item {
                    Text("Bu sonuca geçilemedi. Başka bir sonuç seçebilir veya yeniden arayabilirsin.",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error)
                }
                when (val phase = state.phase) {
                    BookSearchPhase.Idle -> item {
                        Text(if (kind == BookSearchKind.PDF) stringResource(R.string.pdf_search_idle)
                            else if (kind == BookSearchKind.FB2) "Arama açık FB2 kitabının metninde çevrimdışı yapılır."
                            else if (kind == BookSearchKind.DOCX) stringResource(R.string.docx_book_search_idle)
                            else if (kind == BookSearchKind.TXT) "Arama yalnız açık TXT kitabının metninde çevrimdışı yapılır."
                            else "Arama yalnız açık EPUB kitabının metninde çevrimdışı yapılır.", Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    BookSearchPhase.Searching -> item {
                        Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text("Kitap aranıyor…")
                        }
                    }
                    BookSearchPhase.Failed -> item {
                        Text("Kitap aranamadı. Yeniden deneyebilirsin.",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error)
                    }
                    BookSearchPhase.Unsupported -> item { Text(stringResource(R.string.pdf_search_unsupported), Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error) }
                    BookSearchPhase.Limit -> item { Text(stringResource(R.string.pdf_search_limit), Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error) }
                    is BookSearchPhase.Ready -> {
                        if (phase.results.hits.isEmpty()) item {
                            Text("Bu kitapta eşleşme bulunamadı.",
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        else {
                            item { Text("${phase.results.hits.size} sonuç${if (phase.results.truncated) " (ilk 200)" else ""}",
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                            itemsIndexed(phase.results.hits, key = { index, _ -> index }) { _, hit -> key(SearchIdentity(state), SearchIdentity(hit)) {
                                val actionLease = remember { SearchDialogLease() }
                                DisposableEffect(actionLease) { onDispose { actionLease.active = false } }
                                Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Sonuca git") {
                                    if (actionLease.active && fresh()) onSelect(hit)
                                }.padding(vertical = 8.dp).testTag("book-search-hit")) {
                                    Text(hit.title, style = MaterialTheme.typography.titleMedium)
                                    Text(hit.snippet ?: "Metin önizlemesi yok.",
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                                HorizontalDivider()
                            } }
                            if (phase.results.truncated) item {
                                Text("İlk 200 sonuç gösteriliyor. Daha belirgin bir ifade arayabilirsin.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private class SearchDialogLease { var active = true }
private class SearchIdentity(private val value: Any) {
    override fun equals(other: Any?) = other is SearchIdentity && value === other.value
    override fun hashCode() = System.identityHashCode(value)
}
@Composable private fun SearchAction(state: BookSearchState, text: String, enabled: Boolean, action: () -> Unit) {
    key(SearchIdentity(state), SearchIdentity(action)) {
        val lease = remember { SearchDialogLease() }
        DisposableEffect(lease) { onDispose { lease.active = false } }
        TextButton({ if (lease.active) action() }, Modifier.fillMaxWidth(), enabled = enabled) {
            Text(text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

/** Selected text only seeds an editable local query; searching still requires the normal explicit action. */
internal fun selectedBookSearchDraft(text: String): BookSearchState? {
    if ('\u0000' in text || !com.yaprak.reader.reader.SourceRangeAnchors.validUtf16(text)) return null
    val selected = text.trim()
    if (selected.isBlank()) return null
    val query = limitBookSearchQuery(selected)
    return BookSearchState(query = query, selectionDraftTruncated = query != selected)
}

/** A 200 UTF-16-unit cap that does not split a surrogate pair. */
internal fun limitBookSearchQuery(query: String): String {
    if (query.length <= 200) return query
    val limit = if (Character.isHighSurrogate(query[199]) && Character.isLowSurrogate(query[200])) 199 else 200
    return query.take(limit)
}
