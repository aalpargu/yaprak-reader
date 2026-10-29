package com.yaprak.reader.formats.pdf

import android.content.Context
import android.os.Build
import com.yaprak.reader.data.assistant.SelectedTextOriginalSource
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import java.io.File
import kotlinx.coroutines.*

/** Existing data guard owns the file/recovery lease until native disposal and post-file proof finish. */
class PdfSelectedTextSources internal constructor(private val files: VerifiedBookFile,
    private val openDocument: (File) -> PdfDocument, private val nativeSupported: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO) : SelectedTextOriginalSource {
    constructor(context: Context) : this(VerifiedBookFile(File(context.filesDir, "books")),
        PdfDocument::open, { Build.VERSION.SDK_INT >= 35 })
    override suspend fun read(book: Book, request: SelectedTextSourceRequest): List<SelectedTextReference> = withContext(io) {
        val anchor = request.pdfSourceAnchor
        if (!request.isValid || request.sourceFormat != BookFormat.PDF || book.format != BookFormat.PDF ||
            book.id != request.bookId || book.sha256 != request.contentSha256 || !nativeSupported() ||
            book.pageCount == null || anchor == null || anchor.pageIndex !in 0 until book.pageCount)
            failAnswer(BookAnswerFailure.SOURCE)
        try {
            val file = files.resolve(book)
            currentCoroutineContext().ensureActive()
            val document = openDocument(file)
            var primary: Throwable? = null
            try {
                currentCoroutineContext().ensureActive()
                if (document.pageCount != book.pageCount || !document.textSupported) failAnswer(BookAnswerFailure.SOURCE)
                verifyPdfAnnotationSelection(document, request.preview, anchor)
                currentCoroutineContext().ensureActive()
            } catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                try { document.closeAndAwait() }
                catch (cleanup: Throwable) {
                    val first = primary
                    if (first != null) { if (first !== cleanup) first.addSuppressed(cleanup) } else throw cleanup
                }
            }
            currentCoroutineContext().ensureActive()
            if (files.resolve(book).canonicalFile != file.canonicalFile) failAnswer(BookAnswerFailure.SOURCE)
            currentCoroutineContext().ensureActive()
            splitSelectedTextReferences(request.preview)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: BookAnswerException) { throw failure }
        catch (_: Exception) { failAnswer(BookAnswerFailure.SOURCE) }
    }
}
