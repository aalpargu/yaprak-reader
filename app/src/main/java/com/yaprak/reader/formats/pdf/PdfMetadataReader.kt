package com.yaprak.reader.formats.pdf

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.formats.BookMetadata
import java.io.File
import java.io.IOException

class PdfMetadataReader {
    fun read(file: File): BookMetadata {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (error: Exception) {
            descriptor.close()
            when (error) {
                is SecurityException -> throw BookImportException(ImportFailure.PROTECTED)
                is IOException, is IllegalArgumentException -> throw BookImportException(ImportFailure.INVALID)
                else -> throw error
            }
        }
        // PdfRenderer owns and closes the descriptor after a successful construction.
        return renderer.use {
            if (it.pageCount <= 0) throw BookImportException(ImportFailure.INVALID)
            BookMetadata(pageCount = it.pageCount)
        }
    }
}
