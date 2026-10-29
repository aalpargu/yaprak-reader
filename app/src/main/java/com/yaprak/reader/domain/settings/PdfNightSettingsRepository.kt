package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

/** The manual PDF color preference is device-local, independent of the system theme. */
interface PdfNightSettingsRepository {
    val pdfNightMode: Flow<Boolean>
    /** Completes only after the preference is durably written. */
    suspend fun savePdfNightMode(enabled: Boolean)
}
