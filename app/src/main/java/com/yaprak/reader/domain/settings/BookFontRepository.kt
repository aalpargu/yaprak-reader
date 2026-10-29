package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

/** Null inherits the current global EPUB font family. */
interface BookFontRepository {
    fun observe(bookId: String, contentSha256: String): Flow<ReadingFont?>
    suspend fun save(bookId: String, contentSha256: String, font: ReadingFont?)
}
