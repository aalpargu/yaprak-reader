package com.yaprak.reader.domain.settings

/** Clears all nine reflowable book appearance choices to follow the current global settings. */
interface BookAppearanceResetRepository {
    suspend fun reset(bookId: String, contentSha256: String)
}
