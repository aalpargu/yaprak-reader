package com.yaprak.reader.formats.pdf.ink

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** The open PDF's highlighter; null for surfaces without one (thumbnails, tests). */
internal val LocalPdfInk = staticCompositionLocalOf<PdfInkController?> { null }

/** Continuous lists: while the pen is on, a two-finger drag at normal zoom scrolls the list. */
internal val LocalPdfInkScroll = staticCompositionLocalOf<((Offset) -> Unit)?> { null }

/**
 * Pen-mode touch handling for one page. Runs in the Initial pass and consumes everything, so the
 * page's tap, swipe, long-press selection and the parent list's drag never see a pen stroke.
 * One finger paints (or erases); a second finger cancels the stroke and zooms/pans/scrolls instead.
 */
internal suspend fun PointerInputScope.detectPdfInk(
    controller: PdfInkController,
    page: Int,
    projection: () -> PdfInkProjection?,
    zoom: () -> Float,
    pan: () -> Offset,
    transform: (Offset, Float) -> Unit,
    scroll: ((Offset) -> Unit)?,
) {
    val slop = 8.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        down.consume()
        val erasing = controller.tool.value.eraser
        var drawing = false
        fun pagePoint(position: Offset) = projection()?.touchToPage(position, zoom(), pan())
        fun eraseAt(position: Offset) {
            val geometry = projection() ?: return
            val canvas = geometry.touchToCanvas(position, zoom(), pan())
            controller.strokes.value.lastOrNull { it.pageIndex == page && geometry.hits(it, canvas, slop) }
                ?.let(controller::erase)
        }
        try {
            if (erasing) { eraseAt(down.position); drawing = true }
            else pagePoint(down.position)?.let { controller.begin(page, it); drawing = true }
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) {
                    event.changes.forEach { it.consume() }
                    if (drawing && !erasing) controller.finish()
                    drawing = false
                    break
                }
                if (pressed > 1) {
                    if (drawing && !erasing) controller.cancel()
                    drawing = false
                    val factor = event.calculateZoom(); val move = event.calculatePan()
                    if (factor.isFinite() && move.x.isFinite() && move.y.isFinite()) {
                        if (scroll != null && zoom() <= 1f && abs(factor - 1f) < 0.02f) scroll(move) else transform(move, factor)
                    }
                    event.changes.forEach { it.consume() }
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id && it.pressed }
                if (drawing && change != null) {
                    if (erasing) eraseAt(change.position)
                    else pagePoint(change.position)?.let { controller.extend(page, it) }
                }
                event.changes.forEach { it.consume() }
            }
        } finally {
            // Interrupted (pen turned off, page recomposed, gesture cancelled): never save half a stroke.
            if (drawing && !erasing) controller.cancel()
        }
    }
}
