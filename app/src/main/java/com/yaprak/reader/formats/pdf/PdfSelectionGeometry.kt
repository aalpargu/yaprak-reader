package com.yaprak.reader.formats.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.reader.PdfTextPoint
import com.yaprak.reader.reader.PdfSourceAnchor
import com.yaprak.reader.reader.PdfTextBounds

/** Inverse of the current paint/crop/centered graphicsLayer geometry; grey margins are not text. */
internal fun pdfSelectionPoint(touch: Offset, viewport: IntSize, image: IntSize,
    crop: PdfPageCrop, mode: PdfFitMode, zoom: Float, pan: Offset): PdfTextPoint? {
    if (viewport.width <= 0 || viewport.height <= 0 || image.width <= 0 || image.height <= 0 ||
        !touch.x.isFinite() || !touch.y.isFinite() || !pan.x.isFinite() || !pan.y.isFinite() ||
        !zoom.isFinite() || zoom !in 1f..3f || touch.x !in 0f..viewport.width.toFloat() ||
        touch.y !in 0f..viewport.height.toFloat()) return null
    val area = crop.bounds(image.width, image.height)
    val scale = if (mode == PdfFitMode.WIDTH) viewport.width.toDouble() / area.width
        else minOf(viewport.width.toDouble() / area.width, viewport.height.toDouble() / area.height)
    if (!scale.isFinite() || scale <= 0) return null
    val left = (viewport.width - area.width * scale) / 2
    val top = if (mode == PdfFitMode.WIDTH) 0.0 else (viewport.height - area.height * scale) / 2
    val x = ((touch.x - pan.x - viewport.width / 2.0) / zoom + viewport.width / 2.0 - left) / scale
    val y = ((touch.y - pan.y - viewport.height / 2.0) / zoom + viewport.height / 2.0 - top) / scale
    if (x !in 0.0..area.width.toDouble() || y !in 0.0..area.height.toDouble()) return null
    return PdfTextPoint((area.left + x) / image.width, (area.top + y) / image.height).takeIf { it.isValid }
}

/** Projection of verified native bounds into the same cropped paint box, before its shared zoom/pan layer. */
internal fun pdfAnnotationRectangle(anchor: PdfSourceAnchor, bounds: PdfTextBounds, viewport: IntSize,
    image: IntSize, crop: PdfPageCrop, mode: PdfFitMode): Rect? {
    if (viewport.width <= 0 || viewport.height <= 0 || image.width <= 0 || image.height <= 0 || bounds !in anchor.bounds) return null
    val area = crop.bounds(image.width, image.height)
    val left = maxOf(bounds.left / anchor.pageWidth * image.width, area.left.toDouble())
    val top = maxOf(bounds.top / anchor.pageHeight * image.height, area.top.toDouble())
    val right = minOf(bounds.right / anchor.pageWidth * image.width, (area.left + area.width).toDouble())
    val bottom = minOf(bounds.bottom / anchor.pageHeight * image.height, (area.top + area.height).toDouble())
    if (right <= left || bottom <= top) return null
    val scale = if (mode == PdfFitMode.WIDTH) viewport.width.toDouble() / area.width
        else minOf(viewport.width.toDouble() / area.width, viewport.height.toDouble() / area.height)
    val offsetX = (viewport.width - area.width * scale) / 2
    val offsetY = if (mode == PdfFitMode.WIDTH) 0.0 else (viewport.height - area.height * scale) / 2
    return Rect((offsetX + (left - area.left) * scale).toFloat(), (offsetY + (top - area.top) * scale).toFloat(),
        (offsetX + (right - area.left) * scale).toFloat(), (offsetY + (bottom - area.top) * scale).toFloat())
}
