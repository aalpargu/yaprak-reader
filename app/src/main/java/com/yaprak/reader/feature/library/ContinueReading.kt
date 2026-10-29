package com.yaprak.reader.feature.library

import com.yaprak.reader.domain.library.Book

/** The repository supplies lastReadAt only for a matching current content hash. */
internal fun mostRecentlyReadBook(books: List<Book>): Book? = books
    .maxWithOrNull(compareBy<Book> { it.lastReadAt }.thenBy { it.importedAt }.thenBy { it.id })
    ?.takeIf { it.lastReadAt != null }
