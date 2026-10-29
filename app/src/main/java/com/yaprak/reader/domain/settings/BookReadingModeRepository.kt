package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

enum class BookReadingMode { PAGED, SCROLL }

/** Null inherits the current global reading mode. */
interface BookReadingModeRepository {
    fun observe(bookId: String, contentSha256: String): Flow<BookReadingMode?>
    suspend fun save(bookId: String, contentSha256: String, mode: BookReadingMode?)
}
