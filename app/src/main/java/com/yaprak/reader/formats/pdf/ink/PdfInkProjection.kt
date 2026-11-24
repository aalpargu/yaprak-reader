package com.yaprak.reader.formats.pdf.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.yaprak.reader.domain.pdfink.PDF_INK_UNIT
import com.yaprak.reader.domain.pdfink.PdfInkPoint
import com.yaprak.reader.domain.pdfink.PdfInkStroke
import com.yaprak.reader.formats.pdf.PdfFitMode
import com.yaprak.reader.reader.PdfCropArea
import com.yaprak.reader.reader.PdfPageCrop
import kotlin.math.hypot

/**
 * Page units <-> the page's paint box (before its shared zoom/pan graphics layer). Same fit/crop
 * arithmetic as the bitmap painter and [com.yaprak.reader.formats.pdf.pdfSelectionPoint], so ink
 * stays on the same words for every zoom, pan, crop, fit and render width.
 */
internal class PdfInkProjection private constructor(
    private val viewport: IntSize,
    private val image: IntSize,
    private val area: PdfCropArea,
    private val scale: Double,
    private val offsetX: Double,
    private val offsetY: Double,
) {
    /** The visible (cropped) page in canvas pixels; ink is clipped to it. */
    val pageRect: Rect get() = Rect(offsetX.toFloat(), offsetY.toFloat(),
        (offsetX + area.width * scale).toFloat(), (offsetY + area.height * scale).toFloat())

    fun toCanvas(point: PdfInkPoint): Offset = Offset(
        (offsetX + (point.x.toDouble() / PDF_INK_UNIT * image.width - area.left) * scale).toFloat(),
        (offsetY + (point.y.toDouble() / PDF_INK_UNIT * image.height - area.top) * scale).toFloat())

    /** A width stored as a fraction of the page height, in canvas pixels. */
    fun widthPx(width: Int): Float = (width.toDouble() / PDF_INK_UNIT * image.height * scale).toFloat()

    /** Inverse of the centered zoom/pan graphics layer: a touch in the page box -> canvas point. */
    fun touchToCanvas(touch: Offset, zoom: Float, pan: Offset): Offset = Offset(
        (touch.x - pan.x - viewport.width / 2f) / zoom + viewport.width / 2f,
        (touch.y - pan.y - viewport.height / 2f) / zoom + viewport.height / 2f)

    /** Canvas point -> page units, clamped onto the visible page so a stroke never leaves it. */
    fun toPage(canvas: Offset): PdfInkPoint {
        val x = ((canvas.x - offsetX) / scale + area.left).coerceIn(area.left.toDouble(), (area.left + area.width).toDouble())
        val y = ((canvas.y - offsetY) / scale + area.top).coerceIn(area.top.toDouble(), (area.top + area.height).toDouble())
        return PdfInkPoint(unit(x / image.width), unit(y / image.height))
    }

    fun touchToPage(touch: Offset, zoom: Float, pan: Offset) = toPage(touchToCanvas(touch, zoom, pan))

    /** True when [canvas] lies on the stroke's painted band (plus [slop] pixels). */
    fun hits(stroke: PdfInkStroke, canvas: Offset, slop: Float): Boolean = stroke.segments.any { segment ->
        val radius = widthPx(segment.width) / 2 + slop
        val points = segment.points.map(::toCanvas)
        if (points.size == 1) distance(canvas, points[0], points[0]) <= radius
        else points.zipWithNext().any { (a, b) -> distance(canvas, a, b) <= radius }
    }

    companion object {
        fun of(viewport: IntSize, image: IntSize, crop: PdfPageCrop, mode: PdfFitMode): PdfInkProjection? {
            if (viewport.width <= 0 || viewport.height <= 0 || image.width <= 0 || image.height <= 0) return null
            val area = crop.bounds(image.width, image.height)
            val scale = if (mode == PdfFitMode.WIDTH) viewport.width.toDouble() / area.width
                else minOf(viewport.width.toDouble() / area.width, viewport.height.toDouble() / area.height)
            if (!scale.isFinite() || scale <= 0) return null
            val offsetX = (viewport.width - area.width * scale) / 2
            val offsetY = if (mode == PdfFitMode.WIDTH) 0.0 else (viewport.height - area.height * scale) / 2
            return PdfInkProjection(viewport, image, area, scale, offsetX, offsetY)
        }

        private fun unit(fraction: Double) = Math.round(fraction * PDF_INK_UNIT).coerceIn(0L, PDF_INK_UNIT.toLong()).toInt()

        private fun distance(p: Offset, a: Offset, b: Offset): Float {
            val dx = b.x - a.x; val dy = b.y - a.y
            val length = dx * dx + dy * dy
            val t = if (length <= 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0f, 1f)
            return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
        }
    }
}
