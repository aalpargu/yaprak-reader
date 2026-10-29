package com.yaprak.reader.reader

import com.yaprak.reader.domain.search.LibraryTextTarget

/** Opens a whole current EPUB resource or original TXT text part after source proof; indexed offsets are never locators.
 * Verification and its one-use go must run on Main while the verifying coroutine is active.
 */
interface IndexedChapterNavigator {
    suspend fun verifiedChapterHref(target: LibraryTextTarget): String?
    fun goToChapter(href: String): Boolean
}
