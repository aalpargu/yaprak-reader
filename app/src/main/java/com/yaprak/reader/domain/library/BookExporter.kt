package com.yaprak.reader.domain.library

/** Copies the original, locally stored EPUB/PDF into a user-created document. */
interface BookExporter {
    suspend fun export(bookId: String, destinationUri: String): ExportResult
}

sealed interface ExportResult {
    data object Exported : ExportResult

    data class Failed(
        val reason: ExportFailure,
        /** The document provider did not confirm removal of the failed export. */
        val partialFileMayRemain: Boolean = false,
    ) : ExportResult
}

enum class ExportFailure { MISSING_BOOK, CHANGED_SOURCE, ACCESS, IO }
