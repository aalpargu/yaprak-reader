package com.yaprak.reader.reader

/** User-requested OCR of one stable original page; it cannot prove native PDF text anchors. */
interface PdfOcrSource : TextSelectionSource {
    fun prepare(pageIndex: Int): Boolean
}

enum class PdfOcrFailure { BUSY, LIMIT, SOURCE, MODEL }
class PdfOcrException(val reason: PdfOcrFailure) : Exception(reason.name)

internal const val MAX_PDF_OCR_TEXT_UNITS = 5000

internal fun validatedPdfOcrText(text: String?): String? {
    if (text == null) return null
    if (text.length > MAX_PDF_OCR_TEXT_UNITS) throw PdfOcrException(PdfOcrFailure.LIMIT)
    if ('\u0000' in text || !SourceRangeAnchors.validUtf16(text)) throw PdfOcrException(PdfOcrFailure.MODEL)
    return text.takeIf { it.isNotBlank() }
}
