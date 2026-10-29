package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

/** Missing preferences use defaults only after the current book/hash/PDF identity is verified. */
interface BookPdfViewPreferencesRepository {
    fun observe(bookId: String, contentSha256: String): Flow<BookPdfViewPreferences>
    suspend fun saveCrop(bookId: String, contentSha256: String, horizontalPercent: Int, verticalPercent: Int)
    suspend fun saveFit(bookId: String, contentSha256: String, fitMode: PdfFitPreference)
    suspend fun saveSpread(bookId: String, contentSha256: String, doublePagePreferred: Boolean)
    suspend fun saveNavigation(bookId: String, contentSha256: String, mode: PdfNavigationMode)
}

enum class BookPdfViewPreferencesFailure { SOURCE, STALE_BOOK, INVALID_VALUE }
class BookPdfViewPreferencesException(
    val reason: BookPdfViewPreferencesFailure,
    cause: Throwable? = null,
) : Exception(reason.name, cause)
