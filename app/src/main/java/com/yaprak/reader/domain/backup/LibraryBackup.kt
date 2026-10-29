package com.yaprak.reader.domain.backup

/** A complete, unencrypted local copy of the library and global EPUB preferences. */
interface LibraryBackup {
    suspend fun create(destinationUri: String): BackupResult
    /** The first archive version restores into an empty library only. */
    suspend fun restore(sourceUri: String): BackupResult
    /** Finish rollback after an interrupted restore before cleaning orphan book files. */
    suspend fun recoverPendingRestore()
}

sealed interface BackupResult {
    data class Created(val bookCount: Int) : BackupResult
    data class Restored(val bookCount: Int) : BackupResult
    data class Failed(
        val reason: BackupFailure,
        /** A document provider refused to delete a partly written backup. */
        val partialFileMayRemain: Boolean = false,
    ) : BackupResult
}

enum class BackupFailure {
    NOT_EMPTY, INVALID_ARCHIVE, UNSUPPORTED_VERSION, TOO_LARGE,
    CHANGED_SOURCE, ACCESS, IO, RECOVERY_REQUIRED,
}
