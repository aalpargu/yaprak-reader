package com.yaprak.reader.domain.library

interface BookCoverEditor {
    /** Null is an exact absent revision, never a wildcard. */
    suspend fun replace(bookId: String, contentSha256: String, expectedCoverId: String?, sourceUri: String): CoverEditResult
    suspend fun reset(bookId: String, contentSha256: String, expectedCoverId: String?): CoverEditResult
}

enum class CoverEditFailure { INVALID_IMAGE, TOO_LARGE, ACCESS, IO }

sealed interface CoverEditResult {
    data class Changed(val privateFileMayRemain: Boolean = false) : CoverEditResult
    data class Reset(val privateFileMayRemain: Boolean = false) : CoverEditResult
    data object Missing : CoverEditResult
    data object Stale : CoverEditResult
    data class Failed(val reason: CoverEditFailure) : CoverEditResult
}
