package com.yaprak.reader.formats.pdf

import android.content.Context
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.domain.reader.ReadingStateRepository
import com.yaprak.reader.reader.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfReaderLoader(context: Context, private val library: LibraryRepository, private val reading: ReadingStateRepository,
    private val annotations: com.yaprak.reader.domain.pdfannotations.PdfAnnotationRepository? = null,
    private val ocrRecognizer: PdfOcrBitmapRecognizer? = null,
    private val fileMutex: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex(),
    private val validateOcrBook: suspend (com.yaprak.reader.domain.library.Book) -> Unit = {}) : ReaderLoader {
    private val outlineReader = PdfOutlineReader(context.applicationContext)
    private val files = VerifiedBookFile(File(context.filesDir, "books"))

    override suspend fun open(bookId: String): ReaderSession {
        var opened: PdfDocument? = null
        try {
            return withContext(Dispatchers.IO) {
                val book = library.findById(bookId) ?: throw ReaderOpenException(ReaderFailure.MISSING)
                if (book.format != BookFormat.PDF) throw ReaderOpenException(ReaderFailure.UNSUPPORTED)
                val document = PdfDocument.open(files.resolve(book)).also { opened = it }
                val ocrFactory: ((() -> Int?) -> PdfOcrSource)? = ocrRecognizer?.let { recognizer ->
                    { stable -> DocumentPdfOcrSource(book, document, files, recognizer, stable, fileMutex, validateOcrBook) }
                }
                PdfReaderSession(book, document, reading.resume(book.id, book.sha256), annotations, ocrFactory,
                    files, fileMutex, validateOcrBook, outlineReader)
            }
        } catch (error: Throwable) {
            opened?.close()
            throw error
        }
    }
}
