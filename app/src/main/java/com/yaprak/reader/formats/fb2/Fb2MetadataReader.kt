package com.yaprak.reader.formats.fb2

import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.formats.BookMetadata
import java.io.File

/** Validate the complete viewing conversion before committing an original FB2 to the library. */
internal class Fb2MetadataReader {
    suspend fun read(file: File): BookMetadata = try {
        val document = StrictFb2.read(file)
        val validation = File.createTempFile("fb2-validation-", ".epub", file.parentFile)
        try { Fb2Epub.write(validation, fb2ViewHash(file), document) } finally { validation.delete() }
        BookMetadata(document.title, document.authors)
    } catch (failure: Fb2Exception) {
        throw BookImportException(when (failure.reason) {
            Fb2Failure.INVALID -> ImportFailure.INVALID
            Fb2Failure.LIMIT -> ImportFailure.TOO_LARGE
            Fb2Failure.UNSUPPORTED -> ImportFailure.UNSUPPORTED
        })
    }
}
