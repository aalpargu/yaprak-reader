package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

/** A null theme means this book inherits the global EPUB theme. */
interface BookThemeRepository {
    fun observe(bookId: String, contentSha256: String): Flow<ReadingTheme?>
    suspend fun save(bookId: String, contentSha256: String, theme: ReadingTheme?)
}
