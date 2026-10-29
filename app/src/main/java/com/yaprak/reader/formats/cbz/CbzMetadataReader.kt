package com.yaprak.reader.formats.cbz

import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.formats.BookMetadata
import java.io.File

/** Complete original archive validation; filename title fallback is owned by the importer. */
internal class CbzMetadataReader {
    suspend fun read(file: File): BookMetadata = try {
        BookMetadata(pageCount = CbzArchive.read(file).pages.size)
    } catch (error: CbzException) {
        throw BookImportException(when (error.reason) {
            CbzFailure.INVALID -> ImportFailure.INVALID
            CbzFailure.LIMIT -> ImportFailure.TOO_LARGE
            CbzFailure.PROTECTED -> ImportFailure.PROTECTED
            CbzFailure.UNSUPPORTED -> ImportFailure.UNSUPPORTED
        })
    }
}
