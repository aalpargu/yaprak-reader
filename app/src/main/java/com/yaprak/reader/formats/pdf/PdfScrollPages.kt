package com.yaprak.reader.formats.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.yaprak.reader.reader.PdfTextPoint
import com.yaprak.reader.reader.ReaderTap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

/** Only composed near-viewport items own bounded native rasters; no concatenated image or eager document render. */
@Composable
internal fun PdfScrollPages(renderPage: suspend (Int, Int) -> Bitmap,
    dimensions: suspend (Int) -> PdfPageDimensions, pageCount: Int, status: PdfRenderStatus,
    viewportWidth: Int, viewportHeight: Int, onTap: () -> Unit,
    nightMode: Boolean, fitRequest: PdfFitRequest, cropRequest: PdfCropRequest,
    allowed: () -> Boolean, onPrimaryChanged: () -> Unit,
    onTextSelection: ((Int, PdfTextPoint, PdfTextPoint) -> Unit)?,
    annotationPaint: PdfAnnotationPaintState?, drawProofHost: PdfDrawProofView?, onReaderTap: ((ReaderTap) -> Boolean)?) {
    val command by status.scrollNavigation.collectAsState()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = command.pageIndex)
    val failedItems by status.failedScrollItems.collectAsState()
    var completedCommand by remember { mutableLongStateOf(-1) }
    val currentPrimaryChanged by rememberUpdatedState(onPrimaryChanged)
    val currentAllowed by rememberUpdatedState(allowed)
    // The owner may read StateFlow values or lifecycle state, which do not invalidate this composition.
    // Recheck every actual drag/fling delta rather than relying solely on userScrollEnabled's snapshot.
    val navigationGate = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (currentAllowed()) Offset.Zero else available
            override suspend fun onPreFling(available: Velocity): Velocity =
                if (currentAllowed()) Velocity.Zero else available
        }
    }
    val density = LocalDensity.current
    val minimumHeight = with(density) { 96.dp.roundToPx() }
    val readingInset = with(density) { 48.dp.roundToPx() }
    var listCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val pageSizes = remember { mutableStateMapOf<Int, PdfPageDimensions>() }
    val viewport = PdfScrollViewport(viewportWidth, viewportHeight, minimumHeight, readingInset,
        cropRequest.crop, fitRequest.mode)
    SideEffect { status.updateScrollGeometry(viewport) }
    DisposableEffect(drawProofHost) { onDispose { drawProofHost?.updateReadingLine(null) } }
    val lastHeight = if (pageCount - 1 in failedItems) viewportHeight else
        pageSizes[pageCount - 1]?.let { pdfScrollItemHeight(it, viewportWidth, viewportHeight,
            minimumHeight, cropRequest.crop, fitRequest.mode) } ?: viewportHeight
    LaunchedEffect(command, viewport) {
        if (!viewport.isValid || !status.scrolling || status.scrollNavigation.value != command) return@LaunchedEffect
        // BoxWithConstraints may still be measuring when a viewport effect starts.
        // Observe actual placement after the requested next layout, not the requested index alone.
        suspend fun place(offset: Int) {
            list.requestScrollToItem(command.pageIndex, offset)
            snapshotFlow {
                val layout = list.layoutInfo
                !list.isScrollInProgress && layout.viewportSize.width == viewportWidth &&
                    layout.viewportSize.height == viewportHeight &&
                    layout.visibleItemsInfo.any { it.index == command.pageIndex &&
                        it.offset == layout.viewportStartOffset - offset }
            }.first { it }
            currentCoroutineContext().ensureActive()
        }
        place(0)
        if (status.scrollNavigation.value != command) return@LaunchedEffect
        command.anchorY?.let { anchor ->
            val offset = snapshotFlow {
                pageSizes[command.pageIndex]?.let { size ->
                    val height = pdfScrollItemHeight(size, viewportWidth, viewportHeight, minimumHeight,
                        viewport.crop, viewport.fit)
                    val measured = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == command.pageIndex }?.size
                    if (height != null && measured == height) pdfResumeScrollOffset(anchor, size,
                        viewportWidth, viewportHeight, minimumHeight, readingInset, viewport.crop, viewport.fit) else null
                }
            }.first { it != null }!!
            currentCoroutineContext().ensureActive()
            if (status.scrollNavigation.value != command) return@LaunchedEffect
            place(offset)
        }
        currentCoroutineContext().ensureActive()
        if (status.scrollNavigation.value == command) completedCommand = command.revision
    }
    LaunchedEffect(list, status, readingInset) {
        snapshotFlow { list.layoutInfo to completedCommand }.collect { (layout, completed) ->
            val items = layout.visibleItemsInfo.filter { it.offset.toLong() < layout.viewportEndOffset &&
                it.offset.toLong() + it.size > layout.viewportStartOffset }
            val primary = if (completed == status.scrollNavigation.value.revision)
                pdfScrollPrimary(items.map { Triple(it.index, it.offset, it.size) },
                    layout.viewportStartOffset, layout.viewportEndOffset, readingInset) else null
            val previous = status.requestedPage.value
            status.updateScrollViewport(items.map { it.index }, primary, completed)
            if (status.requestedPage.value != previous) currentPrimaryChanged()
            drawProofHost?.invalidate()
        }
    }
    LazyColumn(state = list, userScrollEnabled = allowed(), verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = with(density) { (viewportHeight - lastHeight).coerceAtLeast(0).toDp() }),
        modifier = Modifier.fillMaxSize().nestedScroll(navigationGate).testTag("pdf-continuous-pages")
            .onGloballyPositioned {
                listCoordinates = it
                drawProofHost?.updateReadingLine(if (it.isAttached)
                    it.localToWindow(Offset(0f, minOf(readingInset.toFloat(), viewportHeight / 5f))).y else null)
            }) {
        items(count = pageCount, key = { it }, contentType = { "pdf-original-page" }) { index ->
            val witness = remember(drawProofHost, index) { drawProofHost?.let { host ->
                PdfPageDrawWitness { host.revoke(); host.invalidate() }
            } }
            DisposableEffect(status, drawProofHost, witness, index) {
                status.registerScrollItem(index)
                if (witness != null) drawProofHost?.register(index, witness)
                onDispose {
                    if (witness != null) drawProofHost?.unregister(index, witness)
                    status.unregisterScrollItem(index)
                    pageSizes.remove(index)
                }
            }
            var sizeFailed by remember(index) { mutableStateOf(false) }
            var retry by remember(index) { mutableIntStateOf(0) }
            LaunchedEffect(index, retry) {
                try {
                    val loaded = dimensions(index)
                    currentCoroutineContext().ensureActive()
                    pageSizes[index] = loaded
                    sizeFailed = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    sizeFailed = true
                    status.startRender(index)?.let { status.failRender(index, it) }
                }
            }
            val size = pageSizes[index]
            val itemHeight = size?.let { pdfScrollItemHeight(it, viewportWidth, viewportHeight,
                minimumHeight, cropRequest.crop, fitRequest.mode) }
            LaunchedEffect(index, size, itemHeight) {
                if (size != null && itemHeight == null) status.startRender(index)?.let { status.failRender(index, it) }
            }
            var itemCoordinates by remember(index) { mutableStateOf<LayoutCoordinates?>(null) }
            val displayedHeight = if (index in failedItems) viewportHeight else itemHeight ?: viewportHeight
            Box(Modifier.fillMaxWidth().height(with(density) { displayedHeight.toDp() })
                .onGloballyPositioned { itemCoordinates = it },
                contentAlignment = Alignment.Center) {
                when {
                    sizeFailed || size != null && itemHeight == null -> Surface(Modifier.padding(20.dp)) {
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
                        drawWitness = witness, parentScrolls = true,
                        pointNavigationRevision = command.pointRevision(index), onContentTap = { point, viewport ->
                            val parent = listCoordinates
                            val item = itemCoordinates
                            val tap = if (parent?.isAttached == true && item?.isAttached == true &&
                                item.size.width == viewport.width && item.size.height == viewport.height) {
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
