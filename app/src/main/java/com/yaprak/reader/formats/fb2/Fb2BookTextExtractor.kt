package com.yaprak.reader.formats.fb2

import android.content.Context
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import java.io.File
import kotlinx.coroutines.*

internal class Fb2BookTextExtractor(private val files: VerifiedBookFile, private val io: CoroutineDispatcher = Dispatchers.IO) : BookTextExtractor {
    constructor(context: Context) : this(VerifiedBookFile(File(context.filesDir, "books")))
    override fun supports(book: Book) = book.format == BookFormat.FB2

    override suspend fun extract(book: Book): ExtractedBookText = withContext(io) {
        if (!supports(book)) fail(TextExtractionFailure.UNSUPPORTED)
        if (!LibraryTextTarget(book.id, book.sha256, "Text/b1-p1.xhtml", book.sha256, sourceFormat = BookFormat.FB2).isValid)
            fail(TextExtractionFailure.INVALID)
        try {
            val file = files.resolve(book)
            val parts = fb2OriginalText(StrictFb2.read(file))
            if (parts.size > MAX_INDEX_BOOK_RESOURCES) fail(TextExtractionFailure.LIMIT)
            val chunks = ArrayList<ExtractedTextChunk>()
            var bytes = book.id.toByteArray().size + book.sha256.toByteArray().size + 36L + 5L
            for (part in parts) {
                currentCoroutineContext().ensureActive()
                var start = 0
                while (start < part.text.length) {
                    currentCoroutineContext().ensureActive()
                    var end = minOf(part.text.length, start + MAX_INDEX_CHUNK_TEXT)
                    if (end < part.text.length && part.text[end - 1].isHighSurrogate() && part.text[end].isLowSurrogate()) end--
                    val text = part.text.substring(start, end)
                    if (text.isNotBlank()) {
                        if (chunks.size == MAX_INDEX_BOOK_CHUNKS) fail(TextExtractionFailure.LIMIT)
                        val chunk = ExtractedTextChunk(part.index, chunks.size, part.href, part.sha256, part.title, text, BookFormat.FB2)
                        bytes += indexedChunkStringBytes(book.id, book.sha256, chunk)
                        if (bytes > MAX_INDEX_BOOK_STRING_BYTES) fail(TextExtractionFailure.LIMIT)
                        chunks += chunk
                    }
                    if (end == part.text.length) break
                    start = end - INDEX_CHUNK_OVERLAP
                    if (start > 0 && part.text[start - 1].isHighSurrogate() && part.text[start].isLowSurrogate()) start--
                }
            }
            currentCoroutineContext().ensureActive()
            if (files.resolve(book).canonicalFile != file.canonicalFile) fail(TextExtractionFailure.SOURCE)
            ExtractedBookText(book.id, book.sha256, chunks, parts.size, sourceFormat = BookFormat.FB2)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: TextExtractionException) { throw failure }
        catch (failure: Fb2Exception) { fail(when (failure.reason) {
            Fb2Failure.LIMIT -> TextExtractionFailure.LIMIT
            // Format support is established above; unsupported original grammar is an invalid source.
            Fb2Failure.UNSUPPORTED -> TextExtractionFailure.INVALID
            Fb2Failure.INVALID -> TextExtractionFailure.INVALID
        }) }
        catch (_: Exception) { fail(TextExtractionFailure.SOURCE) }
    }
}

private fun fail(reason: TextExtractionFailure): Nothing = throw TextExtractionException(reason)
