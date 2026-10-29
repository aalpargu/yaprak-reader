package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.search.BookTextScope
import com.yaprak.reader.domain.search.supportsBookSourceRetrieval

/** A single explicit local-screen launch, bound to the rendered reader and lifecycle epoch.
 * It grants no source text, selection, coverage or permission to send anything to a service.
 */
internal class ReaderBookAssistantAction private constructor(
    private val book: Book,
    private val session: Any,
    private val surface: Any,
    private val epoch: Long,
) {
    private var consumed = false
    fun open(currentBook: Book?, currentSession: Any?, currentSurface: Any?, currentEpoch: Long,
        allowed: Boolean, launch: (BookTextScope) -> Unit): Boolean {
        if (consumed || !allowed || currentEpoch != epoch || currentSession !== session || currentSurface !== surface ||
            currentBook?.copy(lastReadAt = null) != book.copy(lastReadAt = null)) return false
        consumed = true
        launch(BookTextScope(book.id, book.sha256))
        return true
    }
    companion object {
        fun create(book: Book, session: Any, surface: Any, epoch: Long): ReaderBookAssistantAction? =
            if (supportsBookSourceRetrieval(book.format) && BookTextScope(book.id, book.sha256).isValid)
                ReaderBookAssistantAction(book, session, surface, epoch) else null
    }
}
