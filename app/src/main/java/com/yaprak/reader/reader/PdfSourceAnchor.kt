package com.yaprak.reader.reader

import java.util.Collections

/** Original native paper units; these are never viewport, cropped bitmap, or invented glyph coordinates. */
data class PdfPagePoint(val x: Int, val y: Int)
data class PdfTextBounds(val left: Double, val top: Double, val right: Double, val bottom: Double)

/** Immutable native evidence. Structural validity alone does not prove a current file or selection. */
class PdfSourceAnchor(
    val pageIndex: Int,
    val pageWidth: Int,
    val pageHeight: Int,
    val start: PdfPagePoint,
    val stop: PdfPagePoint,
    bounds: List<PdfTextBounds>,
) {
    val bounds: List<PdfTextBounds>
    init {
        require(pageIndex >= 0 && pageWidth > 0 && pageHeight > 0)
        require(start.x in 0 until pageWidth && start.y in 0 until pageHeight &&
            stop.x in 0 until pageWidth && stop.y in 0 until pageHeight)
        require(bounds.size in 1..MAX_BOUNDS)
        require(bounds.all { it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() &&
            it.left >= 0 && it.top >= 0 && it.right > it.left && it.bottom > it.top &&
            it.right <= pageWidth && it.bottom <= pageHeight })
        this.bounds = Collections.unmodifiableList(ArrayList(bounds))
    }
    override fun equals(other: Any?): Boolean = other is PdfSourceAnchor &&
        pageIndex == other.pageIndex && pageWidth == other.pageWidth && pageHeight == other.pageHeight &&
        start == other.start && stop == other.stop && bounds == other.bounds
    override fun hashCode(): Int {
        var result = pageIndex
        for (value in listOf(pageWidth, pageHeight, start, stop, bounds)) result = 31 * result + value.hashCode()
        return result
    }
    companion object { const val VERSION = 1; const val MAX_BOUNDS = 256 }
}

/** Same conversion used at the actual native page lease and when its returned evidence is checked. */
internal fun nativePdfPoint(point: PdfTextPoint, width: Int, height: Int): PdfPagePoint {
    require(point.isValid && width > 0 && height > 0)
    return PdfPagePoint((point.x * width).toInt().coerceIn(0, width - 1),
        (point.y * height).toInt().coerceIn(0, height - 1))
}
