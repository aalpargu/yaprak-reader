package com.yaprak.reader.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** An ephemeral preview browser. The source retains ownership of every delivered bitmap. */
@Composable
internal fun PdfThumbnailsDialog(
    requestId: String,
    pageCount: Int,
    currentPage: Int?,
    render: suspend (Int) -> Bitmap,
    isCurrent: () -> Boolean,
    onGo: (Int) -> Boolean,
    onDismiss: () -> Unit,
    initialPage: Int? = currentPage,
) {
    // Material callback holders and tile state must never be reused by a later request.
    key(requestId) {
        val lease = remember { ThumbnailCompositionLease() }
        val currentGate by rememberUpdatedState(isCurrent)
        val currentRender by rememberUpdatedState(render)
        val currentGo by rememberUpdatedState(onGo)
        val currentDismiss by rememberUpdatedState(onDismiss)
        DisposableEffect(lease) { lease.active = true; onDispose { lease.active = false } }
        fun mayAct() = lease.active && currentGate()
        fun dismiss() { if (mayAct()) currentDismiss() }

        var navigationFailure by remember { mutableStateOf<Any?>(null) }
        val navigationFailed = navigationFailure != null
        // A lazy interval's total item count is an Int. Keep every page even when
        // adding a separate grid header would overflow that total.
        val headerInGrid = pageCount < Int.MAX_VALUE
        val seedPage = initialPage?.takeIf { it in 0 until pageCount } ?: 0
        val grid = rememberLazyGridState(initialFirstVisibleItemIndex =
            seedPage + if (headerInGrid && seedPage > 0) 1 else 0)
        val summaryScroll = rememberScrollState()
        LaunchedEffect(navigationFailure) {
            if (navigationFailed && mayAct()) {
                if (headerInGrid) grid.scrollToItem(0) else summaryScroll.scrollTo(0)
            }
        }
        Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                val previewHeight = if (maxHeight < 400.dp) 80.dp else 144.dp
                Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().height(maxHeight),
                    shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                    Column {
                        Text(stringResource(R.string.pdf_thumbnails_title),
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.titleLarge)
                        if (!headerInGrid) PdfThumbnailsSummary(pageCount, navigationFailed,
                            Modifier.fillMaxWidth().heightIn(max = 80.dp).verticalScroll(summaryScroll)
                                .padding(horizontal = 12.dp))
                        LazyVerticalGrid(
                            columns = if (pageCount == 1) GridCells.Fixed(1) else GridCells.Adaptive(128.dp), state = grid,
                            modifier = Modifier.weight(1f).fillMaxWidth().testTag("pdf-thumbnails-grid"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (headerInGrid) item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
                                PdfThumbnailsSummary(pageCount, navigationFailed)
                            }
                            items(count = pageCount.coerceAtLeast(0), key = { it }) { pageIndex ->
                                // Lazy grids may precompose an offscreen cell. Native drawing is
                                // requested only once the cell actually intersects the viewport.
                                val visible by remember(grid, pageIndex) {
                                    derivedStateOf { grid.layoutInfo.visibleItemsInfo.any { it.key == pageIndex } }
                                }
                                PdfThumbnailTile(requestId, pageIndex, pageIndex == currentPage, previewHeight,
                                    visible, { mayAct() }, { currentRender(it) }, { index ->
                                        if (mayAct()) {
                                            val accepted = currentGo(index)
                                            if (accepted) dismiss() else if (mayAct()) {
                                                navigationFailure = Any()
                                            }
                                        }
                                    })
                            }
                        }
                        TextButton(onClick = ::dismiss,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                                .testTag("pdf-thumbnails-close")) {
                            Text(stringResource(R.string.pdf_thumbnails_close), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfThumbnailsSummary(pageCount: Int, navigationFailed: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (navigationFailed) Text(stringResource(R.string.pdf_thumbnails_navigation_error),
            color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.pdf_thumbnails_summary, pageCount))
        Text(stringResource(R.string.pdf_thumbnails_help), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PdfThumbnailTile(
    requestId: String,
    pageIndex: Int,
    currentPage: Boolean,
    previewHeight: androidx.compose.ui.unit.Dp,
    visible: Boolean,
    isCurrent: () -> Boolean,
    render: suspend (Int) -> Bitmap,
    onGo: (Int) -> Unit,
) {
    val lease = remember { ThumbnailCompositionLease() }
    val gate by rememberUpdatedState(isCurrent)
    val renderer by rememberUpdatedState(render)
    val select by rememberUpdatedState(onGo)
    DisposableEffect(lease) { lease.active = true; onDispose { lease.active = false } }
    fun mayAct() = lease.active && gate()
    var attempt by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(requestId, pageIndex, visible, attempt) {
        if (!visible || !mayAct()) return@LaunchedEffect
        bitmap = null
        failed = false
        try {
            val result = renderer(pageIndex)
            currentCoroutineContext().ensureActive()
            if (mayAct()) {
                check(!result.isRecycled && result.width > 0 && result.height > 0)
                bitmap = result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (mayAct()) failed = true
        }
    }
    val pageLabel = stringResource(R.string.pdf_thumbnail_page, pageIndex.toLong() + 1L)
    val currentLabel = stringResource(R.string.pdf_thumbnail_current)
    Card(onClick = { if (mayAct()) select(pageIndex) },
        modifier = Modifier.fillMaxWidth().testTag("pdf-thumbnail-$pageIndex").semantics {
            contentDescription = pageLabel
            if (currentPage) stateDescription = currentLabel
        }, colors = CardDefaults.cardColors(containerColor = if (currentPage)
            MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(previewHeight).background(Color.White), contentAlignment = Alignment.Center) {
                val image = bitmap
                if (image != null) Image(image.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize().testTag("pdf-thumbnail-image-$pageIndex"), contentScale = ContentScale.Fit)
                else if (!failed) CircularProgressIndicator(Modifier.size(28.dp))
            }
            Text(pageLabel, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge)
            if (currentPage) Text(currentLabel, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            if (failed) {
                Text(stringResource(R.string.pdf_thumbnail_failed), Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { if (visible && mayAct()) attempt++ },
                    modifier = Modifier.fillMaxWidth().testTag("pdf-thumbnail-retry-$pageIndex")) {
                    Text(stringResource(R.string.pdf_thumbnail_retry, pageIndex.toLong() + 1L), Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center)
                }
            } else if (bitmap == null) Text(stringResource(R.string.pdf_thumbnail_loading), Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private class ThumbnailCompositionLease { var active = true }
