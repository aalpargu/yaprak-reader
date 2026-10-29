package com.yaprak.reader.formats.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.paint
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.reader.PdfTextPoint
import com.yaprak.reader.domain.pdfannotations.PdfAnnotation
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

/** RGB inversion at draw time; the source bitmap and alpha channel stay intact. */
internal val pdfNightColorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f,
)))

internal enum class PdfFitMode { PAGE, WIDTH }
internal data class PdfFitRequest(val mode: PdfFitMode = PdfFitMode.PAGE, val revision: Long = 0)
internal data class PdfCropRequest(val crop: PdfPageCrop = PdfPageCrop(), val revision: Long = 0)

@Composable
internal fun PdfPage(renderPage: suspend (Int, Int) -> Bitmap, page: Int, onTap: () -> Unit,
    onRenderStarted: (Int) -> Long?, onRendered: (Int, Long) -> Unit,
    nightMode: Boolean = false, onSwipePage: ((delta: Int) -> Boolean)? = null,
    onRenderFailed: ((Int, Long) -> Unit)? = null, fitRequest: PdfFitRequest = PdfFitRequest(),
    cropRequest: PdfCropRequest = PdfCropRequest(), applySystemBarsPadding: Boolean = true,
    onTextSelection: ((Int, PdfTextPoint, PdfTextPoint) -> Unit)? = null,
    verifiedAnnotations: List<PdfAnnotation> = emptyList(),
    // Capture coordinates at pointer down; only the returned action runs after single-tap confirmation.
    onContentTap: ((Offset, IntSize) -> (() -> Unit))? = null,
    drawWitness: PdfPageDrawWitness? = null, parentScrolls: Boolean = false,
    pointNavigationRevision: Long = 0L) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember(page, viewport.width) { mutableStateOf<Bitmap?>(null) }
    var renderTicket by remember(page, viewport.width) { mutableStateOf<Long?>(null) }
    var failed by remember(page) { mutableStateOf(false) }
    // Insets can change width after an error. Only an explicit retry or new page retries it.
    var failedRetry by remember(page) { mutableStateOf<Int?>(null) }
    var retry by remember(page) { mutableIntStateOf(0) }
    var zoom by remember(page, viewport.width, fitRequest, cropRequest, pointNavigationRevision) { mutableFloatStateOf(1f) }
    var pan by remember(page, viewport.width, fitRequest, cropRequest, pointNavigationRevision) { mutableStateOf(Offset.Zero) }
    val contentTap by rememberUpdatedState(onContentTap)
    val swipePage by rememberUpdatedState(onSwipePage)
    val renderFailed by rememberUpdatedState(onRenderFailed)
    val selectText by rememberUpdatedState(onTextSelection)
    var selecting by remember(page, viewport, fitRequest, cropRequest, pointNavigationRevision) { mutableStateOf(false) }
    var selectionMultiTouch by remember(page, viewport, fitRequest, cropRequest, pointNavigationRevision) { mutableStateOf(false) }
    val pendingTap = remember(page, viewport, fitRequest, cropRequest, pointNavigationRevision) { PdfPendingContentTap() }
    val hasViewport = viewport.width > 0 && viewport.height > 0
    LaunchedEffect(page, viewport.width, retry, hasViewport) {
        if (!hasViewport || failedRetry == retry || (bitmap != null && !failed)) return@LaunchedEffect
        val ticket = onRenderStarted(page) ?: return@LaunchedEffect
        failed = false
        try {
            val rendered = renderPage(page, viewport.width)
            currentCoroutineContext().ensureActive()
            bitmap = rendered
            renderTicket = ticket
            onRendered(page, ticket)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
            failedRetry = retry
            renderFailed?.invoke(page, ticket)
        }
    }
    LaunchedEffect(viewport, bitmap, fitRequest, cropRequest, pointNavigationRevision) {
        val image = bitmap ?: return@LaunchedEffect
        val area = cropRequest.crop.bounds(image.width, image.height)
        if (hasViewport) pan = clampPdfPan(pan, zoom, viewport, IntSize(area.width, area.height), fitRequest.mode)
    }
    val drawGeometry = bitmap?.let { image -> PdfDrawGeometry(viewport, IntSize(image.width, image.height),
        cropRequest.crop, fitRequest.mode, zoom, pan, pointNavigationRevision) }
    SideEffect { drawWitness?.update(bitmap, renderTicket, drawGeometry) }
    Box(Modifier.fillMaxSize().background(Color(0xFF232725))
        .then(if (applySystemBarsPadding) Modifier.systemBarsPadding() else Modifier)
        .onSizeChanged { viewport = it }.clipToBounds(), contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            failed -> Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.padding(24.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Bu PDF sayfası görüntülenemedi.")
                    TextButton(onClick = { retry++ }) { Text("Yeniden dene") }
                    TextButton(onClick = onTap) { Text("Araçları göster / gizle") }
                }
            }
            image == null -> CircularProgressIndicator(color = Color.White)
            else -> {
                val area = cropRequest.crop.bounds(image.width, image.height)
                val sourceSize = IntSize(area.width, area.height)
                Box(modifier = Modifier.fillMaxSize()
                    .semantics {
                        contentDescription = "PDF sayfası ${page + 1}"
                        role = Role.Image
                        onClick(label = "Okuma araçlarını göster veya gizle") { onTap(); true }
                    }
                    .pointerInput(page, viewport, fitRequest, cropRequest, pointNavigationRevision) {
                        detectTapGestures(onTap = {
                            // The passive Initial-pass observer invalidates a multi-pointer gesture.
                            if (selectionMultiTouch) return@detectTapGestures
                            val action = pendingTap.action
                            if (action == null) onTap() else action()
                        }, onDoubleTap = {
                            if (selectionMultiTouch) return@detectTapGestures
                            zoom = if (zoom == 1f) 2f else 1f
                            pan = if (hasViewport) clampPdfPan(Offset.Zero, zoom, viewport, sourceSize, fitRequest.mode)
                                else Offset.Zero
                        })
                    }
                    .pointerInput(page, viewport, fitRequest, cropRequest, pointNavigationRevision, selecting, parentScrolls) {
                        val transform: (Offset, Float) -> Unit = { delta, factor ->
                            if (!selecting) {
                                zoom = (zoom * factor).coerceIn(1f, 3f)
                                if (viewport.width > 0 && viewport.height > 0)
                                    pan = clampPdfPan(pan + delta, zoom, viewport, sourceSize, fitRequest.mode)
                            }
                        }
                        if (!selecting) {
                            if (parentScrolls) detectPdfScrollTransforms({ zoom > 1f }, transform)
                            else detectTransformGestures { _, delta, factor, _ -> transform(delta, factor) }
                        }
                    }
                    .pointerInput(page, viewport, image, fitRequest, cropRequest, pointNavigationRevision, onTextSelection != null, zoom, pan) {
                        if (selectText != null) {
                            var start: PdfTextPoint? = null
                            var stop: PdfTextPoint? = null
                            var gestureZoom = zoom
                            var gesturePan = pan
                            var invalid = false
                            try {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { point ->
                                        gestureZoom = zoom; gesturePan = pan; invalid = false
                                        start = pdfSelectionPoint(point, viewport, IntSize(image.width, image.height),
                                            cropRequest.crop, fitRequest.mode, gestureZoom, gesturePan)
                                        stop = start; selecting = start != null && !selectionMultiTouch
                                    },
                                    onDrag = { change, _ ->
                                        if (selectionMultiTouch) { invalid = true; selecting = false }
                                        if (selecting && !invalid) {
                                            change.consume()
                                            stop = pdfSelectionPoint(change.position, viewport, IntSize(image.width, image.height),
                                                cropRequest.crop, fitRequest.mode, gestureZoom, gesturePan)
                                            if (stop == null) invalid = true
                                        }
                                    },
                                    onDragCancel = { selecting = false; start = null; stop = null; invalid = true },
                                    onDragEnd = {
                                        val from = start; val to = stop
                                        val active = selecting && !invalid && !selectionMultiTouch && zoom == gestureZoom && pan == gesturePan
                                        selecting = false; start = null; stop = null
                                        if (active && from != null && to != null) selectText?.invoke(page, from, to)
                                    },
                                )
                            } finally { selecting = false }
                        }
                    }
                    .pointerInput(page, viewport, fitRequest, cropRequest, pointNavigationRevision) {
                        // Observe moves passively; claim only a completed swipe before tap handling.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            // Double-tap confirmation can take 300 ms. A moving list must not reinterpret
                            // this original pointer point against the page's later scroll position.
                            pendingTap.action = contentTap?.invoke(down.position, viewport)
                            selectionMultiTouch = currentEvent.changes.count { it.pressed } > 1
                            val threshold = maxOf(48.dp.toPx(), viewport.width * 0.2f)
                            var eligible = !selecting && zoom == 1f && !pdfBaselineClipped(viewport, sourceSize, fitRequest.mode) &&
                                swipePage != null &&
                                currentEvent.changes.count { it.pressed } == 1
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.id != down.id }) selectionMultiTouch = true
                                if (selecting || zoom != 1f || event.changes.any { it.id != down.id }) eligible = false
                                val change = event.changes.firstOrNull { it.id == down.id }
                                val released = change?.let { it.previousPressed && !it.pressed } == true
                                // Check before consuming our own up: Compose synthesizes consumed cancellation ups.
                                val cancelled = released && change.isConsumed &&
                                    change.position == change.previousPosition &&
                                    change.uptimeMillis == change.previousUptimeMillis
                                if (change == null || cancelled) eligible = false
                                val displacement = change?.position?.minus(down.position) ?: Offset.Zero
                                val completedSwipe = eligible && released && abs(displacement.x) >= threshold &&
                                    abs(displacement.x) >= 2 * abs(displacement.y)
                                if (completedSwipe) change?.consume()
                                awaitPointerEvent(PointerEventPass.Final)
                                if (zoom != 1f || pdfBaselineClipped(viewport, sourceSize, fitRequest.mode)) eligible = false
                                if (completedSwipe && eligible) {
                                    swipePage?.invoke(if (displacement.x < 0f) 1 else -1)
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .onGloballyPositioned { drawWitness?.position(it) }
                    .drawWithContent {
                        drawContent()
                        drawGeometry?.let { drawWitness?.painted(image, renderTicket, it) }
                    }
                    .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }
                    // Image clips at its own viewport before panning. Paint the selected source area here;
                    // the outer viewport clips only after the geometry transform.
                    .paint(BitmapPainter(image.asImageBitmap(), srcOffset = IntOffset(area.left, area.top),
                        srcSize = sourceSize), sizeToIntrinsics = false,
                        alignment = if (fitRequest.mode == PdfFitMode.WIDTH) Alignment.TopCenter else Alignment.Center,
                        contentScale = if (fitRequest.mode == PdfFitMode.WIDTH) ContentScale.FillWidth else ContentScale.Fit,
                        colorFilter = if (nightMode) pdfNightColorFilter else null)) {
                    Canvas(Modifier.matchParentSize()) {
                        verifiedAnnotations.filter { it.anchor.pageIndex == page }.forEach { annotation ->
                            val color = annotation.highlightColor?.paintColor() ?: return@forEach
                            annotation.anchor.bounds.forEach { bounds ->
                                pdfAnnotationRectangle(annotation.anchor, bounds, viewport, IntSize(image.width, image.height),
                                    cropRequest.crop, fitRequest.mode)?.let { rectangle ->
                                    drawRect(color, rectangle.topLeft, rectangle.size)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class PdfPendingContentTap { var action: (() -> Unit)? = null }

private fun PdfHighlightColor.paintColor() = when (this) {
    PdfHighlightColor.YELLOW -> Color(0x66FFD54F)
    PdfHighlightColor.GREEN -> Color(0x665FCC84)
    PdfHighlightColor.BLUE -> Color(0x66529EE8)
    PdfHighlightColor.PINK -> Color(0x66ED82AD)
}

/** Clamp against the fitted page; a height change retains scale but can reduce pan bounds. */
private fun clampPdfPan(pan: Offset, zoom: Float, viewport: IntSize, image: IntSize, mode: PdfFitMode): Offset {
    val fit = pdfBaseScale(viewport, image, mode)
    val maxX = ((image.width * fit * zoom - viewport.width) / 2).coerceAtLeast(0f)
    if (mode == PdfFitMode.WIDTH) {
        // FillWidth starts at the top; graphicsLayer still scales around the viewport center.
        val topPan = viewport.height * (zoom - 1f) / 2
        val bottomPan = (viewport.height * (zoom + 1f) / 2 - image.height * fit * zoom).coerceAtMost(topPan)
        return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(bottomPan, topPan))
    }
    val maxY = ((image.height * fit * zoom - viewport.height) / 2).coerceAtLeast(0f)
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

private fun pdfBaseScale(viewport: IntSize, image: IntSize, mode: PdfFitMode): Float =
    if (mode == PdfFitMode.WIDTH) viewport.width.toFloat() / image.width
    else minOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)

private fun pdfBaselineClipped(viewport: IntSize, image: IntSize, mode: PdfFitMode): Boolean {
    if (viewport.width <= 0 || viewport.height <= 0) return true
    val fit = pdfBaseScale(viewport, image, mode)
    return image.width * fit > viewport.width + 0.5f || image.height * fit > viewport.height + 0.5f
}
