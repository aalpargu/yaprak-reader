package com.yaprak.reader.formats.pdf

import com.yaprak.reader.reader.PdfPageCrop

/** Effective geometry only: repeating a preference revision must not move the reading point. */
internal data class PdfScrollViewport(
    val width: Int,
    val height: Int,
    val minimumItemHeight: Int,
    val readingInset: Int,
    val crop: PdfPageCrop,
    val fit: PdfFitMode,
) {
    val isValid get() = width > 0 && height > 0 && minimumItemHeight > 0 && readingInset >= 0
}
