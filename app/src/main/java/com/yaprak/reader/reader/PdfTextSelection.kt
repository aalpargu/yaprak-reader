package com.yaprak.reader.reader

/** Fractions of the original, uncropped page. No screen pixels or invented text offsets. */
data class PdfTextPoint(val x: Double, val y: Double) {
    val isValid get() = x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0
}

/** Ephemeral native selection. Source page is independent from the spread's reading anchor. */
data class PdfSelectedText(val pageIndex: Int, val text: String, val sourceAnchor: PdfSourceAnchor? = null)
