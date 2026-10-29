package com.yaprak.reader.formats.pdf

import android.graphics.Bitmap
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.reader.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-owner request identity; file/render/model work remains on IO and never creates an anchor. */
internal class DocumentPdfOcrSource(
    private val book: Book,
    private val document: PdfDocument,
    private val files: VerifiedBookFile,
    private val recognizer: PdfOcrBitmapRecognizer,
    private val stablePage: () -> Int?,
    private val fileMutex: Mutex,
    private val validateBook: suspend (Book) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PdfOcrSource {
    private data class Request(val page: Int, val id: String = UUID.randomUUID().toString())
    @Volatile private var request: Request? = null
    private val capturing = AtomicBoolean(false)

    override fun prepare(pageIndex: Int): Boolean {
        if (book.format != BookFormat.PDF || book.pageCount != document.pageCount || !document.isOpen ||
            pageIndex !in 0 until document.pageCount || stablePage() != pageIndex) return false
        request = Request(pageIndex)
        return true
    }

    override fun clear() { request = null }
    override fun isCurrent(requestId: String?): Boolean = request?.let {
        document.isOpen && stablePage() == it.page && (requestId == null || it.id == requestId)
    } == true

    override suspend fun capture(): SelectedText? {
        val captured = request ?: return null
        if (!isCurrent(captured.id)) throw CancellationException("OCR page changed")
        if (!capturing.compareAndSet(false, true)) throw PdfOcrException(PdfOcrFailure.BUSY)
        var bitmap: Bitmap? = null
        try {
            val text = withContext(io) {
                fileMutex.withLock {
                    ensureActive()
                    validateBook(book)
                    files.resolve(book)
                    ensureActive()
                    bitmap = document.render(captured.page, 2048)
                }
                ensureActive()
                val recognized = validatedPdfOcrText(recognizer.recognize(checkNotNull(bitmap)))
                ensureActive()
                fileMutex.withLock {
                    validateBook(book)
                    files.resolve(book)
                    ensureActive()
                }
                recognized
            }
            currentCoroutineContext().ensureActive()
            if (request !== captured || !isCurrent(captured.id)) throw CancellationException("OCR request changed")
            return text?.let { SelectedText(it, pdfPageIndex = captured.page, sourceSelectionId = captured.id, pdfOcr = true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PdfOcrException) {
            throw failure
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw PdfOcrException(PdfOcrFailure.SOURCE)
        } finally {
            // Returning from a different dispatcher may promptly rethrow caller cancellation.
            // Release the source lease inside cleanup, before that hand-back can happen.
            withContext(NonCancellable + io) {
                try { bitmap?.takeUnless { it.isRecycled }?.recycle() }
                finally { capturing.set(false) }
            }
        }
    }
}
