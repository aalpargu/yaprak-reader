package com.yaprak.reader.formats.pdf

import android.content.Context
import android.os.Build
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.reader.BookSearchException
import com.yaprak.reader.reader.BookSearchFailure
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*

internal const val MAX_PDF_INDEX_RAW_TEXT = 32L * 1024 * 1024

/** Explicit index build only. Caller holds the shared restore/removal file mutex.
 * Traverses every native text page, closes the renderer, then rechecks the complete private file.
 * No bitmap/OCR/upload or index/progress writes occur in this adapter.
 */
internal class PdfBookTextExtractor(
    private val files: VerifiedBookFile,
    private val openDocument: (File) -> PdfDocument,
    private val nativeSupported: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BookTextExtractor {
    constructor(context: Context) : this(VerifiedBookFile(File(context.filesDir, "books")),
        PdfDocument::open, { Build.VERSION.SDK_INT >= 35 })

    override fun supports(book: Book) = book.format == BookFormat.PDF && nativeSupported()

    override suspend fun extract(book: Book): ExtractedBookText = withContext(io) {
        if (!supports(book)) fail(TextExtractionFailure.UNSUPPORTED)
        if (!LibraryTextTarget(book.id, book.sha256, "", book.sha256,
                sourceFormat = BookFormat.PDF, pdfPageIndex = 0).isValid) fail(TextExtractionFailure.INVALID)
        try {
            val file = files.resolve(book)
            currentCoroutineContext().ensureActive()
            val document = openDocument(file)
            var primary: Throwable? = null
            val result = try {
                currentCoroutineContext().ensureActive()
                extractIndexPdfText(document, book)
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                try { document.closeAndAwait() }
                catch (cleanup: Throwable) {
                    val first = primary
                    if (first != null) { if (first !== cleanup) first.addSuppressed(cleanup) }
                    else throw cleanup
                }
            }
            currentCoroutineContext().ensureActive()
            if (files.resolve(book).canonicalFile != file.canonicalFile) fail(TextExtractionFailure.SOURCE)
            currentCoroutineContext().ensureActive()
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: TextExtractionException) { throw failure }
        catch (failure: BookSearchException) {
            fail(when (failure.reason) {
                BookSearchFailure.UNSUPPORTED -> TextExtractionFailure.UNSUPPORTED
                BookSearchFailure.LIMIT -> TextExtractionFailure.LIMIT
                else -> TextExtractionFailure.SOURCE
            })
        } catch (_: Exception) { fail(TextExtractionFailure.SOURCE) }
    }
}

/** Uses complete bounded native page text, including empty pages; never returns a partial book. */
internal suspend fun extractIndexPdfText(document: PdfDocument, book: Book): ExtractedBookText {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    if (!document.textAvailable) fail(TextExtractionFailure.UNSUPPORTED)
    if (document.pageCount > MAX_INDEX_BOOK_RESOURCES) fail(TextExtractionFailure.LIMIT)
    if (book.pageCount != null && book.pageCount != document.pageCount) fail(TextExtractionFailure.SOURCE)
    val chunks = ArrayList<ExtractedTextChunk>()
    var rawText = 0L
    var stringBytes = (book.id.toByteArray().size + book.sha256.toByteArray().size + 36 + 5).toLong()
    for (page in 0 until document.pageCount) {
        caller.ensureActive()
        val extracted = document.extractText(page)
        caller.ensureActive()
        rawText += extracted.rawUtf16
        if (rawText > MAX_PDF_INDEX_RAW_TEXT) fail(TextExtractionFailure.LIMIT)
        val text = extracted.text
        if (text.isEmpty()) continue
        val hash = indexedPdfTextHash(text)
        var start = 0
        while (start < text.length) {
            caller.ensureActive()
            var end = minOf(text.length, start + MAX_INDEX_CHUNK_TEXT)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            val chunkText = text.substring(start, end)
            if (chunkText.isNotBlank()) {
                if (chunks.size == MAX_INDEX_BOOK_CHUNKS) fail(TextExtractionFailure.LIMIT)
                val chunk = ExtractedTextChunk(page, chunks.size, "", hash, null, chunkText,
                    BookFormat.PDF, page)
                stringBytes += indexedChunkStringBytes(book.id, book.sha256, chunk)
                if (stringBytes > MAX_INDEX_BOOK_STRING_BYTES) fail(TextExtractionFailure.LIMIT)
                chunks += chunk
            }
            if (end == text.length) break
            start = end - INDEX_CHUNK_OVERLAP
            if (start > 0 && text[start - 1].isHighSurrogate() && text[start].isLowSurrogate()) start--
        }
    }
    caller.ensureActive()
    if (!document.textAvailable) fail(TextExtractionFailure.SOURCE)
    return ExtractedBookText(book.id, book.sha256, chunks, document.pageCount, sourceFormat = BookFormat.PDF)
}

/** Full whitespace-normalized native page text; deliberately distinct from raw PDF/XHTML bytes. */
internal fun indexedPdfTextHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun fail(reason: TextExtractionFailure): Nothing = throw TextExtractionException(reason)
