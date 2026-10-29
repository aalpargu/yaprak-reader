package com.yaprak.reader.domain.library

/** Exports only this book's saved records to a newly SAF-created document. */
interface BookStudyExporter {
    suspend fun export(bookId: String, contentSha256: String, destinationUri: String): StudyExportResult

    /** Legacy implementations remain TXT-only and must not claim that a different format was written. */
    suspend fun export(
        bookId: String,
        contentSha256: String,
        destinationUri: String,
        format: StudyExportFormat,
    ): StudyExportResult = if (format == StudyExportFormat.TEXT) {
        export(bookId, contentSha256, destinationUri)
    } else {
        StudyExportResult.Failed(StudyExportFailure.UNSUPPORTED_FORMAT, partialFileMayRemain = true)
    }
}

enum class StudyExportFormat(val mimeType: String, val extension: String) {
    TEXT("text/plain", "txt"),
    MARKDOWN("text/markdown", "md"),
    ANKI_TSV("text/tab-separated-values", "tsv"),
    PDF("application/pdf", "pdf"),
    ANKI_STUDY_TSV("text/tab-separated-values", "tsv"),
    NOTION_CSV("text/csv", "csv"),
    OBSIDIAN_ZIP("application/zip", "zip"),
}

sealed interface StudyExportResult {
    data class Exported(val noteCount: Int, val wordCount: Int, val passageCount: Int,
        /** One import row per saved record; an attached note belongs to its passage's card. */
        val ankiCardCount: Int? = null) : StudyExportResult
    data class Failed(
        val reason: StudyExportFailure,
        /** The provider did not confirm removal of the failed, newly created target. */
        val partialFileMayRemain: Boolean = false,
    ) : StudyExportResult
}

enum class StudyExportFailure { MISSING_BOOK, CHANGED_BOOK, NO_RECORDS, TOO_LARGE, ACCESS, IO, UNSUPPORTED_FORMAT }

const val MAX_STUDY_EXPORT_BYTES = 8 * 1024 * 1024
