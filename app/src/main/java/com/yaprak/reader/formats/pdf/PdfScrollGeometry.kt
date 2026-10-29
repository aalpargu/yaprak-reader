package com.yaprak.reader.formats.pdf

import com.yaprak.reader.reader.PdfPageCrop
import kotlin.math.ceil

internal data class PdfPageDimensions(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
}

/** Compose packed constraints need a finite bound. An overlong width-fit page fails, never clips silently. */
internal const val MAX_PDF_SCROLL_ITEM_HEIGHT = 32_000

internal fun pdfScrollItemHeight(dimensions: PdfPageDimensions, width: Int, viewportHeight: Int,
    minimumHeight: Int, crop: PdfPageCrop, fit: PdfFitMode): Int? {
    if (width <= 0 || viewportHeight <= 0 || minimumHeight <= 0) return null
    val area = crop.bounds(dimensions.width, dimensions.height)
    val natural = ceil(width.toDouble() * area.height / area.width)
    val desired = if (fit == PdfFitMode.PAGE) minOf(natural, viewportHeight.toDouble()) else natural
    return desired.takeIf { it.isFinite() && it <= MAX_PDF_SCROLL_ITEM_HEIGHT }
        ?.toInt()?.coerceAtLeast(minimumHeight.coerceAtMost(viewportHeight))
}

/** The page at the upper reading line. A short explicit target stays primary above a taller neighbor. */
internal fun pdfScrollPrimary(items: List<Triple<Int, Int, Int>>, viewportStart: Int, viewportEnd: Int,
    readingInset: Int): Int? {
    if (viewportEnd <= viewportStart || readingInset < 0) return null
    val visible = items.filter { (page, offset, size) -> page >= 0 && size > 0 &&
        offset.toLong() < viewportEnd && offset.toLong() + size > viewportStart }.sortedBy { it.second }
    val line = viewportStart.toLong() + minOf(readingInset.toLong(), (viewportEnd.toLong() - viewportStart) / 5)
    return (visible.firstOrNull { (_, offset, size) -> offset.toLong() + size > line } ?: visible.lastOrNull())?.first
}
