package com.yaprak.reader.formats.cbr

import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.formats.BookMetadata
import com.yaprak.reader.formats.cbz.CbzException
import com.yaprak.reader.formats.cbz.CbzFailure
import java.io.File

/** Admission includes every RAR entry and native raster. Original bytes remain in the library. */
internal class CbrMetadataReader(private val cache: CbrPublicationCache) {
    suspend fun read(file: File): BookMetadata = try {
        cache.acquire(file).use { BookMetadata(pageCount = it.archive.pages.size) }
    } catch (failure: CbrException) {
        throw BookImportException(when (failure.reason) {
            CbrFailure.INVALID -> ImportFailure.INVALID
            CbrFailure.LIMIT -> ImportFailure.TOO_LARGE
            CbrFailure.PROTECTED -> ImportFailure.PROTECTED
            CbrFailure.UNSUPPORTED -> ImportFailure.UNSUPPORTED
        })
    } catch (failure: CbzException) {
        throw BookImportException(when (failure.reason) {
            CbzFailure.INVALID -> ImportFailure.INVALID
            CbzFailure.LIMIT -> ImportFailure.TOO_LARGE
            CbzFailure.PROTECTED -> ImportFailure.PROTECTED
            CbzFailure.UNSUPPORTED -> ImportFailure.UNSUPPORTED
        })
    }
}
