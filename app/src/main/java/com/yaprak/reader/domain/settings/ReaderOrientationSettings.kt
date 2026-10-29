package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

/** Device-local reader preference; AUTO delegates orientation to the Android system. */
enum class ReaderOrientation { AUTO, PORTRAIT, LANDSCAPE }

/** Shared by EPUB and PDF readers, independent of platform orientation constants. */
interface ReaderOrientationSettingsRepository {
    val orientation: Flow<ReaderOrientation>
    /** Completes only after the preference is durably written. */
    suspend fun saveOrientation(orientation: ReaderOrientation)
}
