package com.yaprak.reader.formats.pdf.ink

import com.yaprak.reader.domain.pdfink.PDF_INK_UNIT
import com.yaprak.reader.domain.pdfink.PdfInkPoint
import com.yaprak.reader.domain.pdfink.PdfInkTextRect
import com.yaprak.reader.formats.pdf.PdfDocument
import com.yaprak.reader.reader.PdfSelectedText
import com.yaprak.reader.reader.PdfTextPoint

/**
 * Word/line rectangles from the PDF's own text layer (Android 15+ native selection, the same
 * source the text-selection highlights use). A collapsed request returns the word under the point.
 * Older Android or a page without text yields null; the pen then uses a default thickness.
 */
internal fun pdfInkTextLookup(document: PdfDocument): PdfInkTextLookup? =
    if (!document.textSupported) null
    else PdfInkTextLookup { page, start, stop ->
        if (!document.isOpen) null
        else pdfInkRects(document.selectText(page, start.fraction(), stop.fraction()))
    }

internal fun pdfInkRects(selected: PdfSelectedText?): List<PdfInkTextRect>? {
    val anchor = selected?.sourceAnchor ?: return null
    val width = anchor.pageWidth.toDouble(); val height = anchor.pageHeight.toDouble()
    return anchor.bounds.map { PdfInkTextRect(it.left / width, it.top / height, it.right / width, it.bottom / height) }
}

private fun PdfInkPoint.fraction() = PdfTextPoint(x.toDouble() / PDF_INK_UNIT, y.toDouble() / PDF_INK_UNIT)
