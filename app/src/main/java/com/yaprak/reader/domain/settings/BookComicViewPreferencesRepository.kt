package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

data class BookComicViewPreferences(val doublePagePreferred: Boolean = false)

/** Missing means single only after the exact current CBZ identity has been verified. */
interface BookComicViewPreferencesRepository {
    fun observe(bookId: String, contentSha256: String): Flow<BookComicViewPreferences>
    suspend fun saveSpread(bookId: String, contentSha256: String, doublePagePreferred: Boolean)
}

enum class BookComicViewPreferencesFailure { SOURCE, STALE_BOOK, INVALID_VALUE }
class BookComicViewPreferencesException(val reason: BookComicViewPreferencesFailure, cause: Throwable? = null) : Exception(reason.name, cause)
