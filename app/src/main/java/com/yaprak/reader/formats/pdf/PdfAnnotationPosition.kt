package com.yaprak.reader.formats.pdf

import com.yaprak.reader.reader.PdfSourceAnchor
import kotlin.math.floor

/** Original native geometry only. Reverse gestures still open the first highlighted source line. */
internal fun pdfAnnotationPosition(anchor: PdfSourceAnchor, pageCount: Int): PdfResumePosition? {
    if (anchor.pageIndex !in 0 until pageCount) return null
    val originalY = anchor.bounds.minOf { it.top }
    val fixedY = floor(originalY / anchor.pageHeight * 1_000_000).toInt().coerceIn(0, 1_000_000)
    return PdfResumePosition(anchor.pageIndex, fixedY)
}
