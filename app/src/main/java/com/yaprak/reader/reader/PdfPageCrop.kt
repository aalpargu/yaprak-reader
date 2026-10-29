package com.yaprak.reader.reader

/** View-only symmetric margins for the open PDF; percentages refer to the whole source page. */
data class PdfPageCrop(val horizontalPercent: Int = 0, val verticalPercent: Int = 0) {
    init {
        require(horizontalPercent in 0..20 && verticalPercent in 0..20)
    }

    val isOriginal: Boolean get() = horizontalPercent == 0 && verticalPercent == 0

    fun bounds(sourceWidth: Int, sourceHeight: Int): PdfCropArea {
        require(sourceWidth > 0 && sourceHeight > 0)
        val left = (sourceWidth.toLong() * horizontalPercent / 100L).toInt()
        val top = (sourceHeight.toLong() * verticalPercent / 100L).toInt()
        return PdfCropArea(left, top, sourceWidth - 2 * left, sourceHeight - 2 * top)
    }
}

data class PdfCropArea(val left: Int, val top: Int, val width: Int, val height: Int) {
    init { require(left >= 0 && top >= 0 && width > 0 && height > 0) }
}

/** Applies a visual preference without rasterizing a page or recording reading position. */
fun interface PdfCropController { fun apply(crop: PdfPageCrop) }
