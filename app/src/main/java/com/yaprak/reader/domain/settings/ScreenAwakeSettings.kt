package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

/** A device-local preference shared by EPUB and PDF readers, separate from book appearance. */
interface ScreenAwakeSettingsRepository {
    val keepScreenOn: Flow<Boolean>
    /** Completes only after the preference is durably written. */
    suspend fun saveKeepScreenOn(enabled: Boolean)
}
