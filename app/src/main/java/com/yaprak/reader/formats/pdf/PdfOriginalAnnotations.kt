package com.yaprak.reader.formats.pdf

import android.os.Build
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.pdfannotations.PdfAnnotationOriginalSource
import com.yaprak.reader.domain.pdfannotations.PdfAnnotationSourceException
import com.yaprak.reader.domain.pdfannotations.PdfAnnotation
import com.yaprak.reader.domain.pdfannotations.MAX_PDF_ANNOTATIONS_PER_BOOK
import com.yaprak.reader.reader.PdfSourceAnchor
import com.yaprak.reader.reader.PdfTextPoint
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Caller owns the shared file/recovery guard; every durable write reopens fresh verified original bytes. */
class PdfOriginalAnnotations(directory: File) : PdfAnnotationOriginalSource {
    private val files = VerifiedBookFile(directory)
    override suspend fun verify(book: Book, text: String, anchor: PdfSourceAnchor) = verifyInputs(book, listOf(text to anchor))
    override suspend fun verifyAll(book: Book, annotations: List<PdfAnnotation>) {
        require(annotations.size <= MAX_PDF_ANNOTATIONS_PER_BOOK)
        verifyInputs(book, annotations.map { it.text to it.anchor })
    }
    private suspend fun verifyInputs(book: Book, selections: List<Pair<String, PdfSourceAnchor>>) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 35 || book.format != BookFormat.PDF || book.pageCount == null ||
            selections.any { it.second.pageIndex !in 0 until book.pageCount })
            throw PdfAnnotationSourceException()
        var document: PdfDocument? = null
        try {
            document = PdfDocument.open(files.resolve(book))
            if (document.pageCount != book.pageCount || !document.textSupported) throw PdfAnnotationSourceException()
            selections.forEach { (text, anchor) ->
                currentCoroutineContext().ensureActive(); verifyPdfAnnotationSelection(document, text, anchor)
            }
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: PdfAnnotationSourceException) { throw failure }
        catch (failure: Exception) { throw PdfAnnotationSourceException(failure) }
        finally { document?.close() }
    }
}

/** Native evidence must match exactly. Similar text/overlapping rectangles are not a successful proof. */
internal suspend fun verifyPdfAnnotationSelection(document: PdfDocument, text: String, anchor: PdfSourceAnchor) {
    val start = PdfTextPoint((anchor.start.x + .5) / anchor.pageWidth, (anchor.start.y + .5) / anchor.pageHeight)
    val stop = PdfTextPoint((anchor.stop.x + .5) / anchor.pageWidth, (anchor.stop.y + .5) / anchor.pageHeight)
    val actual = document.selectText(anchor.pageIndex, start, stop)
    if (actual == null || actual.text != text || actual.sourceAnchor != anchor) throw PdfAnnotationSourceException()
}
