package com.yaprak.reader.domain.settings

const val MAX_PDF_CROP_PERCENT = 20

/** A persistent fit choice; manual zoom and pan remain surface-local. */
enum class PdfFitPreference { PAGE, WIDTH }

enum class PdfNavigationMode { PAGED, VERTICAL_SCROLL, HORIZONTAL_SCROLL }

/** View-only preferences for one current PDF content version, independent of its position. */
data class BookPdfViewPreferences(
    val horizontalPercent: Int = 0,
    val verticalPercent: Int = 0,
    val fitMode: PdfFitPreference = PdfFitPreference.PAGE,
    val doublePagePreferred: Boolean = false,
    val navigationMode: PdfNavigationMode = PdfNavigationMode.PAGED,
) {
    init {
        require(horizontalPercent in 0..MAX_PDF_CROP_PERCENT &&
            verticalPercent in 0..MAX_PDF_CROP_PERCENT)
    }
}
