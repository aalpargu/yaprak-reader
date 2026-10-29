package com.yaprak.reader.data.exporting

import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportFormat

/** Coherent saved records, with no provider or book-file access. */
fun interface BookStudySource {
    suspend fun snapshot(bookId: String, contentSha256: String): BookStudySnapshot

    suspend fun snapshot(bookId: String, contentSha256: String, format: StudyExportFormat): BookStudySnapshot =
        if (format == StudyExportFormat.TEXT) snapshot(bookId, contentSha256)
        else throw StudyExportException(StudyExportFailure.UNSUPPORTED_FORMAT)

    /** Typed export formats require actual saved fields; legacy TXT must not be parsed into records. */
    suspend fun snapshotRecords(bookId: String, contentSha256: String): StudyRecordsSnapshot =
        throw StudyExportException(StudyExportFailure.UNSUPPORTED_FORMAT)
}

data class BookStudySnapshot(
    val utf8: ByteArray,
    val noteCount: Int,
    val wordCount: Int,
    val passageCount: Int,
)

internal class StudyExportException(val reason: StudyExportFailure) : Exception(reason.name)
