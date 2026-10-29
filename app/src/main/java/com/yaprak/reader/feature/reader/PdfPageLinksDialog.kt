package com.yaprak.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R
import com.yaprak.reader.reader.PdfPageLinksFailure

private class PdfLinksCompositionLease { var active = true }

@Composable
internal fun PdfPageLinksDialog(state: PdfPageLinksState, onSelect: (PdfPageLinksState, String) -> Unit,
    onRetry: (PdfPageLinksState) -> Unit, onClose: () -> Unit) {
    val request = state.request ?: return
    key(request) {
        val lease = remember { PdfLinksCompositionLease() }
        DisposableEffect(lease) { onDispose { lease.active = false } }
        val latest by rememberUpdatedState(state)
        Dialog(onDismissRequest = { if (lease.active) onClose() },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
                Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight),
                    shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                    LazyColumn(Modifier.fillMaxWidth().testTag("pdf-page-links-list"),
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Text(stringResource(R.string.pdf_page_links_heading, request.pageIndex + 1),
                                style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.pdf_page_links_scope), style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.loading) item { Text(stringResource(R.string.pdf_page_links_loading)) }
                        if (state.resolvingId != null) item { Text(stringResource(R.string.pdf_page_links_resolving)) }
                        state.failure?.let { failure -> item {
                            Text(stringResource(when (failure) {
                                PdfPageLinksFailure.LIMIT -> R.string.pdf_page_links_limit
                                PdfPageLinksFailure.UNSUPPORTED -> R.string.pdf_page_links_unsupported
                                PdfPageLinksFailure.INTERRUPTED -> R.string.pdf_page_links_interrupted
                                PdfPageLinksFailure.NAVIGATION -> R.string.pdf_page_links_navigation_failed
                                PdfPageLinksFailure.SOURCE -> R.string.pdf_page_links_failed
                            }), color = MaterialTheme.colorScheme.error)
                            TextButton({ if (lease.active && latest === state) onRetry(state) },
                                enabled = !state.loading && state.resolvingId == null,
                                modifier = Modifier.fillMaxWidth().testTag("pdf-page-links-retry")) {
                                Text(stringResource(R.string.pdf_page_links_retry))
                            }
                        } }
                        if (!state.loading && state.failure == null && state.links.isEmpty()) item {
                            Text(stringResource(R.string.pdf_page_links_empty))
                        }
                        items(state.links, key = { it.id }) { link ->
                            TextButton({ if (lease.active && latest === state) onSelect(state, link.id) },
                                enabled = !state.loading && state.resolvingId == null && state.failure == null,
                                modifier = Modifier.fillMaxWidth().testTag("pdf-page-link-${link.ordinal}")) {
                                Text(stringResource(R.string.pdf_page_links_item, link.ordinal, link.targetPageIndex + 1),
                                    Modifier.fillMaxWidth())
                            }
                        }
                        item {
                            TextButton({ if (lease.active) onClose() },
                                modifier = Modifier.fillMaxWidth().testTag("pdf-page-links-close")) {
                                Text(stringResource(R.string.pdf_thumbnails_close))
                            }
                        }
                    }
                }
            }
        }
    }
}
