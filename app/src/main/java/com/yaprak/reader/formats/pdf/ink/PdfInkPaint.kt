package com.yaprak.reader.formats.pdf.ink

import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.domain.pdfink.PdfInkMode
import com.yaprak.reader.domain.pdfink.PdfInkSegment

/** Marker inks: light enough that black text stays black under a multiply blend. */
internal fun PdfHighlightColor.inkColor() = when (this) {
    PdfHighlightColor.YELLOW -> Color(0xFFFFE45C)
    PdfHighlightColor.GREEN -> Color(0xFF9EE6A0)
    PdfHighlightColor.BLUE -> Color(0xFFA4CFFF)
    PdfHighlightColor.PINK -> Color(0xFFFFA9CB)
}

/**
 * How one stroke is composited over the page bitmap. Day: multiply (like a real highlighter, the
 * text under it keeps its darkness). Night (inverted page) or Android < 10, where multiply is not
 * available for layers: a translucent normal blend.
 */
internal data class PdfInkBlend(val mode: BlendMode, val alpha: Float)

internal fun pdfInkBlend(night: Boolean, sdk: Int = Build.VERSION.SDK_INT) = when {
    night -> PdfInkBlend(BlendMode.SrcOver, 0.38f)
    sdk >= Build.VERSION_CODES.Q -> PdfInkBlend(BlendMode.Multiply, 0.9f)
    else -> PdfInkBlend(BlendMode.SrcOver, 0.42f)
}

/** Aligned bands keep their round caps inside the words: the ends are pulled in by part of the cap. */
internal fun pdfInkCanvasPoints(segment: PdfInkSegment, mode: PdfInkMode, projection: PdfInkProjection): List<Offset> {
    val points = segment.points.map(projection::toCanvas)
    if (mode != PdfInkMode.ALIGNED || points.size != 2) return points
    val (a, b) = points
    val inset = minOf(projection.widthPx(segment.width) * 0.3f, kotlin.math.abs(b.x - a.x) / 2)
    return if (b.x >= a.x) listOf(Offset(a.x + inset, a.y), Offset(b.x - inset, b.y))
        else listOf(Offset(a.x - inset, a.y), Offset(b.x + inset, b.y))
}

/**
 * Paints a whole stroke into one offscreen layer with opaque ink, then composites that layer once.
 * Where the stroke crosses itself (or two lines of an aligned stroke touch) the color therefore
 * does not get darker; separate strokes still add up like real ink.
 */
internal fun DrawScope.drawPdfInk(segments: List<PdfInkSegment>, mode: PdfInkMode, color: PdfHighlightColor,
    projection: PdfInkProjection, night: Boolean) {
    if (segments.isEmpty()) return
    val blend = pdfInkBlend(night)
    val page = projection.pageRect
    clipRect(page.left, page.top, page.right, page.bottom) {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(page, Paint().apply { blendMode = blend.mode; alpha = blend.alpha })
            val ink = color.inkColor()
            segments.forEach { segment ->
                val points = pdfInkCanvasPoints(segment, mode, projection)
                val width = projection.widthPx(segment.width)
                if (points.size == 1 || points.all { it == points[0] }) {
                    drawCircle(ink, width / 2, points[0])
                } else {
                    val path = Path().apply {
                        moveTo(points[0].x, points[0].y)
                        // Midpoint quadratic smoothing: soft curves instead of finger-sample corners.
                        for (index in 1 until points.size - 1) {
                            val mid = Offset((points[index].x + points[index + 1].x) / 2, (points[index].y + points[index + 1].y) / 2)
                            quadraticTo(points[index].x, points[index].y, mid.x, mid.y)
                        }
                        lineTo(points.last().x, points.last().y)
                    }
                    drawPath(path, ink, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            canvas.restore()
        }
    }
}
