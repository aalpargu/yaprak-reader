package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

/** Null inherits the current global EPUB text alignment. */
interface BookTextAlignmentRepository {
    fun observe(bookId: String, contentSha256: String): Flow<ReadingTextAlignment?>
    suspend fun save(bookId: String, contentSha256: String, alignment: ReadingTextAlignment?)
}
