package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

/** A null size means this book inherits the global EPUB font size. */
interface BookFontSizeRepository {
    fun observe(bookId: String, contentSha256: String): Flow<Int?>

    /** A non-null size must be 75-200 percent in steps of five. */
    suspend fun save(bookId: String, contentSha256: String, fontSizePercent: Int?)
}
