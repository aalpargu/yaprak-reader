package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.reader.ReaderSurface

/** A saved native preference command is acknowledged independently of any page draw or navigation. */
internal class ReaderComicBookViewPreferencesBinding {
    fun apply(target: ReaderSurface, book: Book, state: ComicBookViewPreferencesState,
        isCurrent: () -> Boolean = { true }): Boolean {
        val owner = state.owner ?: return false
        val value = state.preferences ?: return false
        val controller = target.comicSpread ?: return false
        if (!book.format.comic || owner.bookId != book.id || owner.contentSha256 != book.sha256 ||
            state.status != ComicBookViewStatus.APPLYING || !isCurrent()) return false
        if (!controller.restore(value.doublePagePreferred)) return false
        return isCurrent() && target.comicSpread === controller && controller.preferred.value == value.doublePagePreferred
    }
}
