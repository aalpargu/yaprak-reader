package com.yaprak.reader.formats.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.yaprak.reader.reader.PdfTextPoint
import com.yaprak.reader.reader.ReaderTap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect

/** Full-width original pages in a lazy horizontal list, with independent bounded vertical overflow. */
@Composable
internal fun PdfHorizontalPages(renderPage: suspend (Int, Int) -> Bitmap,
    dimensions: suspend (Int) -> PdfPageDimensions, pageCount: Int, status: PdfRenderStatus,
    viewportWidth: Int, viewportHeight: Int, onTap: () -> Unit,
    nightMode: Boolean, fitRequest: PdfFitRequest, cropRequest: PdfCropRequest,
    allowed: () -> Boolean, onPrimaryChanged: () -> Unit,
    onTextSelection: ((Int, PdfTextPoint, PdfTextPoint) -> Unit)?,
    annotationPaint: PdfAnnotationPaintState?, drawProofHost: PdfDrawProofView?, onReaderTap: ((ReaderTap) -> Boolean)?) {
    val command by status.scrollNavigation.collectAsState()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = command.pageIndex)
    val failedItems by status.failedScrollItems.collectAsState()
    val requestedPage by status.requestedPage.collectAsState()
    val retryPending = remember { mutableStateMapOf<Int, Boolean>() }
    val sizes = remember { mutableStateMapOf<Int, PdfPageDimensions>() }
    val settledItems = remember { mutableStateMapOf<Int, Pair<Long, PdfScrollViewport>>() }
    var completedCommand by remember { mutableStateOf<Pair<Long, PdfScrollViewport>?>(null) }
    var listCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentAllowed by rememberUpdatedState(allowed)
    val currentPrimaryChanged by rememberUpdatedState(onPrimaryChanged)
    val gate = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource) =
                if (currentAllowed()) Offset.Zero else available
            override suspend fun onPreFling(available: Velocity) =
                if (currentAllowed()) Velocity.Zero else available
        }
    }
    val density = LocalDensity.current
    val minimumHeight = with(density) { 96.dp.roundToPx() }
    val readingInset = with(density) { 48.dp.roundToPx() }
    val viewport = PdfScrollViewport(viewportWidth, viewportHeight, minimumHeight, readingInset,
        cropRequest.crop, fitRequest.mode)
    SideEffect { status.updateScrollGeometry(viewport) }
    DisposableEffect(drawProofHost) { onDispose { drawProofHost?.updateReadingLine(null) } }
    LaunchedEffect(command, viewport) {
        if (!viewport.isValid || !status.scrolling || !status.horizontalScrolling ||
            status.scrollNavigation.value != command) return@LaunchedEffect
        // A viewport change can start this effect inside BoxWithConstraints' measure.
        // Request the next layout instead of forcing a nested synchronous remeasure.
        list.requestScrollToItem(command.pageIndex)
        snapshotFlow {
            val layout = list.layoutInfo
            !list.isScrollInProgress && layout.viewportSize.width == viewportWidth &&
                layout.viewportSize.height == viewportHeight &&
                layout.visibleItemsInfo.any { it.index == command.pageIndex &&
                    it.offset == layout.viewportStartOffset }
        }.first { it }
        currentCoroutineContext().ensureActive()
        if (status.scrollNavigation.value != command) return@LaunchedEffect
        val placement = command.revision to viewport
        snapshotFlow { settledItems[command.pageIndex] }.first { it == placement }
        currentCoroutineContext().ensureActive()
        if (status.scrollNavigation.value == command) completedCommand = placement
    }
    LaunchedEffect(list, status, readingInset, viewport, command) {
        snapshotFlow { list.layoutInfo to completedCommand?.takeIf {
            it.second == viewport && it.first == command.revision && retryPending[command.pageIndex] != true
        }?.first }.collect { (layout, stamp) ->
            val completed = stamp ?: -1L
            val items = layout.visibleItemsInfo.filter { it.offset.toLong() < layout.viewportEndOffset &&
                it.offset.toLong() + it.size > layout.viewportStartOffset }
            val primary = if (completed == status.scrollNavigation.value.revision)
                pdfScrollPrimary(items.map { Triple(it.index, it.offset, it.size) },
                    layout.viewportStartOffset, layout.viewportEndOffset, readingInset) else null
            val before = status.requestedPage.value
            status.updateScrollViewport(items.map { it.index }, primary, completed)
            if (status.requestedPage.value != before) currentPrimaryChanged()
            drawProofHost?.invalidate()
        }
    }
    // Document order stays left-to-right even when the application's locale is RTL.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        LazyRow(state = list, userScrollEnabled = allowed(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().nestedScroll(gate).testTag("pdf-horizontal-pages")
                .onGloballyPositioned {
                    listCoordinates = it
                    drawProofHost?.updateReadingLine(if (it.isAttached)
                        it.localToWindow(Offset(0f, minOf(readingInset.toFloat(), viewportHeight / 5f))).y else null)
                }) {
            items(count = pageCount, key = { it }, contentType = { "pdf-original-horizontal-page" }) { index ->
                val witness = remember(drawProofHost, index) { drawProofHost?.let { host ->
                    PdfPageDrawWitness { host.revoke(); host.invalidate() }
                } }
                DisposableEffect(status, drawProofHost, witness, index) {
                    status.registerScrollItem(index)
                    if (witness != null) drawProofHost?.register(index, witness)
                    onDispose {
                        if (witness != null) drawProofHost?.unregister(index, witness)
                        status.unregisterScrollItem(index); sizes.remove(index); settledItems.remove(index); retryPending.remove(index)
                    }
                }
                var sizeFailed by remember(index) { mutableStateOf(false) }
                var retry by remember(index) { mutableIntStateOf(0) }
                LaunchedEffect(index, retry) {
                    try {
                        val loaded = dimensions(index)
                        currentCoroutineContext().ensureActive()
                        sizes[index] = loaded; sizeFailed = false
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        sizeFailed = true; status.startRender(index)?.let { status.failRender(index, it) }
                    }
                }
                val size = sizes[index]
                val height = size?.let { pdfScrollItemHeight(it, viewportWidth, viewportHeight,
                    minimumHeight, cropRequest.crop, fitRequest.mode) }
                LaunchedEffect(index, size, height) {
                    if (size != null && height == null) status.startRender(index)?.let { status.failRender(index, it) }
                }
                val vertical = rememberScrollState()
                var measuredHeight by remember(index) { mutableIntStateOf(0) }
                var itemCoordinates by remember(index) { mutableStateOf<LayoutCoordinates?>(null) }
                val nativeFailed = index in failedItems
                LaunchedEffect(index, command, viewport, size, height, nativeFailed, requestedPage) {
                    if (index != command.pageIndex || !viewport.isValid || size == null || height == null) return@LaunchedEffect
                    val placement = command.revision to viewport
                    if (nativeFailed) {
                        if (requestedPage == index) {
                            settledItems.remove(index); retryPending[index] = true
                        }
                        return@LaunchedEffect
                    }
                    // An already placed page returning through lazy composition is ordinary browsing.
                    // A failed raster shrank its error view and clamped vertical scroll; retry must place its target again.
                    if (completedCommand == placement && retryPending[index] != true) return@LaunchedEffect
                    // Wait for the actual child/scroll limits before acknowledging a point command.
                    snapshotFlow { measuredHeight == height &&
                        vertical.maxValue == (height - viewportHeight).coerceAtLeast(0) }.first { it }
                    currentCoroutineContext().ensureActive()
                    if (status.scrollNavigation.value != command) return@LaunchedEffect
                    val offset = command.anchorY?.let { pdfResumeScrollOffset(it, size, viewportWidth,
                        viewportHeight, minimumHeight, readingInset, viewport.crop, viewport.fit) } ?: 0
                    vertical.scrollTo(offset)
                    currentCoroutineContext().ensureActive()
                    if (status.scrollNavigation.value == command) {
                        settledItems[index] = placement
                        retryPending.remove(index)
                    }
                }
                val displayedHeight = if (index in failedItems) viewportHeight else height ?: viewportHeight
                Box(Modifier.width(with(density) { viewportWidth.toDp() }).fillMaxHeight()
                    .verticalScroll(vertical, enabled = allowed())) {
                    Box(Modifier.fillMaxWidth().height(with(density) { displayedHeight.toDp() })
                        .onGloballyPositioned { itemCoordinates = it; measuredHeight = it.size.height },
                        contentAlignment = Alignment.Center) {
                        when {
                            sizeFailed || size != null && height == null -> Surface(Modifier.padding(20.dp)) {
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(if (sizeFailed) "Bu PDF sayfasının boyutları okunamadı."
                                        else "Bu sayfa genişliğe sığdırıldığında çok uzun. Araçlardan Sayfaya sığdır seçeneğini kullan.")
                                    if (sizeFailed) TextButton({ retry++ }) { Text("Yeniden dene") }
                                    TextButton(onTap) { Text("Araçları göster / gizle") }
                                }
                            }
                            size == null -> CircularProgressIndicator()
                            else -> PdfPage(renderPage, index, onTap, status::startRender,
                                { rendered, ticket -> status.finishRender(rendered, ticket); drawProofHost?.invalidate() },
                                nightMode = nightMode, onRenderFailed = status::failRender,
                                fitRequest = fitRequest, cropRequest = cropRequest, applySystemBarsPadding = false,
                                onTextSelection = onTextSelection,
                                verifiedAnnotations = (annotationPaint as? PdfAnnotationPaintState.Ready)?.annotations.orEmpty(),
                                drawWitness = witness, parentScrolls = true, pointNavigationRevision = command.pointRevision(index),
                                onContentTap = { point, childViewport ->
                                    val parent = listCoordinates; val item = itemCoordinates
                                    val tap = if (parent?.isAttached == true && item?.isAttached == true &&
                                        item.size.width == childViewport.width && item.size.height == childViewport.height) {
                                        val position = parent.localPositionOf(item, point)
                                        ReaderTap.fromPixels(position.x, position.y, parent.size.width, parent.size.height)
                                    } else ReaderTap(Float.NaN, Float.NaN)
                                    val dispatch: () -> Unit = { if (onReaderTap?.invoke(tap) != true) onTap() }
                                    dispatch
                                })
                        }
                    }
                }
            }
        }
    }
}
