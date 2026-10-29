package com.yaprak.reader.formats.docx

import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.formats.BookMetadata
import java.io.File

internal const val DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/** Validate complete conversion before the original is committed; cancellation remains cancellation. */
internal class DocxMetadataReader {
    suspend fun read(file: File): BookMetadata = try {
        val document = StrictDocx.read(file)
        val validation = File.createTempFile("docx-validation-", ".epub", file.parentFile)
        try { DocxEpub.write(validation, docxViewHash(file), document) } finally { validation.delete() }
        BookMetadata(document.metadata.title, document.metadata.authors)
    } catch (failure: DocxException) {
        throw BookImportException(when (failure.reason) {
            DocxFailure.INVALID -> ImportFailure.INVALID
            DocxFailure.LIMIT -> ImportFailure.TOO_LARGE
            DocxFailure.UNSUPPORTED -> ImportFailure.UNSUPPORTED
        })
    }
}
