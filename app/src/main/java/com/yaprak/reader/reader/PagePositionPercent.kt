package com.yaprak.reader.reader

/** Selected original page number / original count. This describes position, never read coverage. */
internal fun pagePositionPercent(pageIndex: Int?, pageCount: Int): Int? {
    if (pageIndex == null || pageCount <= 0 || pageIndex !in 0 until pageCount) return null
    // Floor avoids showing the last-page endpoint before the last actual page in a large book.
    return (((pageIndex.toLong() + 1) * 100) / pageCount).toInt()
}
