package com.yaprak.reader.formats.pdf

import android.graphics.RectF
import com.yaprak.reader.reader.PdfPageCrop
import kotlin.math.roundToInt

/** Inverse of the exact painted crop/zoom/pan. Reopening preserves original Y at baseline zoom. */
internal fun PdfDrawGeometry.resumeAnchorY(localReadingY: Float): Int? {
    if (!localReadingY.isFinite()) return null
    val image = RectF()
    if (!destination(image)) return null
    val area = crop.bounds(source.width, source.height)
    val visibleY = localReadingY.coerceIn(image.top, image.bottom)
    val originalY = area.top + (visibleY - image.top).toDouble() / image.height() * area.height
    return (originalY / source.height * 1_000_000).roundToInt().coerceIn(0, 1_000_000)
}

/** Uses original dimensions, never a pending LazyColumn placeholder. The list clamps document ends. */
internal fun pdfResumeScrollOffset(anchorY: Int, dimensions: PdfPageDimensions, width: Int,
    viewportHeight: Int, minimumHeight: Int, readingInset: Int, crop: PdfPageCrop, fit: PdfFitMode): Int? {
    if (anchorY !in 0..1_000_000 || readingInset < 0) return null
    val itemHeight = pdfScrollItemHeight(dimensions, width, viewportHeight, minimumHeight, crop, fit) ?: return null
    val area = crop.bounds(dimensions.width, dimensions.height)
    val scale = if (fit == PdfFitMode.WIDTH) width.toDouble() / area.width
        else minOf(width.toDouble() / area.width, itemHeight.toDouble() / area.height)
    val imageHeight = area.height * scale
    val imageTop = if (fit == PdfFitMode.WIDTH) 0.0 else (itemHeight - imageHeight) / 2
    val originalY = anchorY / 1_000_000.0 * dimensions.height
    val localY = imageTop + (originalY - area.top).coerceIn(0.0, area.height.toDouble()) * scale
    val line = minOf(readingInset.toDouble(), viewportHeight / 5.0)
    return (localY - line).roundToInt().coerceIn(0, itemHeight - 1)
}
