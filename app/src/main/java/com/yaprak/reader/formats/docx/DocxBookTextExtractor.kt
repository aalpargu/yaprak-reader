package com.yaprak.reader.formats.docx

import android.content.Context
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import java.io.File
import kotlinx.coroutines.*

/** Complete original canonical document text, including referenced notes. No private-EPUB text harvesting. */
internal class DocxBookTextExtractor(private val files: VerifiedBookFile,
    private val io: CoroutineDispatcher=Dispatchers.IO): BookTextExtractor {
    constructor(context: Context): this(VerifiedBookFile(File(context.filesDir,"books")))
    override fun supports(book: Book)=book.format==BookFormat.DOCX
    override suspend fun extract(book: Book): ExtractedBookText=withContext(io) {
        if(!supports(book)) fail(TextExtractionFailure.UNSUPPORTED)
        if(!LibraryTextTarget(book.id,book.sha256,DOCX_INDEX_HREF,book.sha256,sourceFormat=BookFormat.DOCX).isValid)
            fail(TextExtractionFailure.INVALID)
        if(book.byteSize<=0) fail(TextExtractionFailure.INVALID)
        if(book.byteSize>MAX_DOCX_PACKAGE_BYTES) fail(TextExtractionFailure.LIMIT)
        try {
            val original=files.resolve(book)
            val part=docxOriginalText(StrictDocx.read(original))
            if(part.text.toByteArray(Charsets.UTF_8).size>MAX_INDEX_RESOURCE_BYTES) fail(TextExtractionFailure.LIMIT)
            val chunks=ArrayList<ExtractedTextChunk>()
            var bytes=book.id.toByteArray().size+book.sha256.toByteArray().size+36L+5L
            var start=0
            while(start<part.text.length) {
                currentCoroutineContext().ensureActive()
                var end=minOf(part.text.length,start+MAX_INDEX_CHUNK_TEXT)
                if(end<part.text.length && part.text[end-1].isHighSurrogate() && part.text[end].isLowSurrogate()) end--
                val text=part.text.substring(start,end)
                if(text.isNotBlank()) {
                    if(chunks.size>=MAX_INDEX_BOOK_CHUNKS) fail(TextExtractionFailure.LIMIT)
                    val chunk=ExtractedTextChunk(0,chunks.size,DOCX_INDEX_HREF,part.sha256,DOCX_INDEX_TITLE,text,BookFormat.DOCX)
                    bytes+=indexedChunkStringBytes(book.id,book.sha256,chunk)
                    if(bytes>MAX_INDEX_BOOK_STRING_BYTES) fail(TextExtractionFailure.LIMIT)
                    chunks+=chunk
                }
                if(end==part.text.length) break
                start=end-INDEX_CHUNK_OVERLAP
                if(start>0 && part.text[start-1].isHighSurrogate() && part.text[start].isLowSurrogate()) start--
            }
            currentCoroutineContext().ensureActive()
            if(files.resolve(book).canonicalFile!=original.canonicalFile) fail(TextExtractionFailure.SOURCE)
            ExtractedBookText(book.id,book.sha256,chunks,1,sourceFormat=BookFormat.DOCX)
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(failure: TextExtractionException) { throw failure }
        catch(failure: DocxException) { fail(if(failure.reason==DocxFailure.LIMIT) TextExtractionFailure.LIMIT else TextExtractionFailure.INVALID) }
        catch(_: Exception) { fail(TextExtractionFailure.SOURCE) }
    }
}

private fun fail(reason: TextExtractionFailure): Nothing=throw TextExtractionException(reason)
