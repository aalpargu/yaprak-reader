package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

/** Device-local opt-in shared by EPUB/PDF, independent of appearance and library backup. */
interface VolumeKeySettingsRepository {
    val volumePageTurning: Flow<Boolean>
    suspend fun saveVolumePageTurning(enabled: Boolean)
}
