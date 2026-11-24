package com.yaprak.reader.formats.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.reader.PdfTextPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Handles share the native annotation projection, including crop, fit, zoom and pan. */
@Composable
internal fun BoxScope.PdfSelectionOverlay(source: PdfTextSelectionSource, selected: PdfSelectionPreview,
    viewport: IntSize, image: IntSize, crop: PdfPageCrop, mode: PdfFitMode, zoom: Float, pan: Offset) {
    val anchor = selected.selection.sourceAnchor ?: return
    val first = anchor.bounds.firstOrNull() ?: return
    val last = anchor.bounds.last()
    val startRect = pdfAnnotationRectangle(anchor, first, viewport, image, crop, mode) ?: return
    val endRect = pdfAnnotationRectangle(anchor, last, viewport, image, crop, mode) ?: return
    fun screen(point: Offset) = (point - Offset(viewport.width / 2f, viewport.height / 2f)) * zoom +
        Offset(viewport.width / 2f, viewport.height / 2f) + pan
    val start = screen(Offset(startRect.left, startRect.center.y))
    val end = screen(Offset(endRect.right, endRect.center.y))
    val handleGap = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }
    val startHandle = screen(Offset(startRect.left, startRect.bottom)) + Offset(0f, handleGap)
    val endHandle = screen(Offset(endRect.right, endRect.bottom)) + Offset(0f, handleGap)
    val currentStart by rememberUpdatedState(start)
    val currentEnd by rememberUpdatedState(end)
    val scope = rememberCoroutineScope()
    var pending by remember(source) { mutableStateOf<Job?>(null) }
    fun update(from: PdfTextPoint, to: PdfTextPoint) {
        pending?.cancel()
        pending = scope.launch {
            try { source.preview(selected.page, from, to) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { source.clear() }
        }
    }
    for ((isStart, position) in listOf(true to startHandle, false to endHandle)) {
        val current by rememberUpdatedState(selected)
        Box(Modifier.align(Alignment.TopStart).offset { IntOffset(position.x.roundToInt() - 24.dp.roundToPx(), position.y.roundToInt() - 24.dp.roundToPx()) }
            .size(48.dp).testTag(if (isStart) "pdf-selection-start" else "pdf-selection-end")
            .semantics { contentDescription = if (isStart) "Seçimin başlangıcını sürükle" else "Seçimin sonunu sürükle" }
            .pointerInput(source, isStart, viewport, zoom, pan) {
                var pointer = Offset.Zero
                detectDragGestures(onDragStart = { pointer = (if (isStart) currentStart else currentEnd) }, onDrag = { change, delta ->
                    change.consume(); pointer += delta
                    pdfSelectionPoint(pointer, viewport, image, crop, mode, zoom, pan)?.let { point ->
                        if (isStart) update(point, current.stop) else update(current.start, point)
                    }
                })
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp).background(Color(0xFF1976D2), CircleShape))
        }
    }
    val toolbarAlignment = if ((start.y + end.y) / 2 < viewport.height / 2f) Alignment.BottomCenter else Alignment.TopCenter
    Surface(Modifier.align(toolbarAlignment).padding(8.dp).testTag("pdf-selection-toolbar"),
        shape = MaterialTheme.shapes.medium, shadowElevation = 4.dp) {
        Row {
            TextButton({ source.showTools() }) { Text("Seçim araçları") }
            TextButton({ pending?.cancel(); source.clear() }) { Text("Vazgeç") }
        }
    }
}
