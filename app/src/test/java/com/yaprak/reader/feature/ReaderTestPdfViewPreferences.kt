package com.yaprak.reader.feature

import com.yaprak.reader.domain.settings.BookPdfViewPreferences
import com.yaprak.reader.domain.settings.BookPdfViewPreferencesRepository
import com.yaprak.reader.domain.settings.PdfFitPreference
import kotlinx.coroutines.flow.MutableStateFlow

/** Controlled durable boundary for Activity fixtures whose PDF publication is supplied by a fake loader. */
internal class ReaderTestPdfViewPreferences : BookPdfViewPreferencesRepository {
    private val rows = mutableMapOf<Pair<String, String>, MutableStateFlow<BookPdfViewPreferences>>()
    private fun row(id: String, hash: String) = rows.getOrPut(id to hash) { MutableStateFlow(BookPdfViewPreferences()) }
    override fun observe(bookId: String, contentSha256: String) = row(bookId, contentSha256)
    override suspend fun saveCrop(bookId: String, contentSha256: String, horizontalPercent: Int, verticalPercent: Int) {
        val row = row(bookId, contentSha256)
        row.value = row.value.copy(horizontalPercent = horizontalPercent, verticalPercent = verticalPercent)
    }
    override suspend fun saveFit(bookId: String, contentSha256: String, fitMode: PdfFitPreference) {
        val row = row(bookId, contentSha256); row.value = row.value.copy(fitMode = fitMode)
    }
    override suspend fun saveSpread(bookId: String, contentSha256: String, doublePagePreferred: Boolean) {
        val row = row(bookId, contentSha256); row.value = row.value.copy(doublePagePreferred = doublePagePreferred)
    }
    override suspend fun saveNavigation(bookId: String, contentSha256: String, mode: com.yaprak.reader.domain.settings.PdfNavigationMode) {
        val row = row(bookId, contentSha256); row.value = row.value.copy(navigationMode = mode)
    }
}
