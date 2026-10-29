package com.yaprak.reader.reader

import com.yaprak.reader.domain.search.LibraryTextTarget

/** Rechecks a real page's full extracted text and file proof; no excerpt/glyph locator.
 * Verification and its one-use go run on Main in the same active caller coroutine.
 */
interface IndexedPdfPageNavigator {
    val supported: Boolean get() = true
    suspend fun verifiedPageIndex(target: LibraryTextTarget): Int?
    fun goToPage(pageIndex: Int): Boolean
}
