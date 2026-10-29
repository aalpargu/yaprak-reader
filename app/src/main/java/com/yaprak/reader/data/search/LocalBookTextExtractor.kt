package com.yaprak.reader.data.search

import android.content.Context
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.BookTextExtractor
import com.yaprak.reader.domain.search.TextExtractionException
import com.yaprak.reader.domain.search.TextExtractionFailure
import com.yaprak.reader.formats.epub.EpubBookTextExtractor
import com.yaprak.reader.formats.pdf.PdfBookTextExtractor
import com.yaprak.reader.formats.txt.TxtBookTextExtractor
import com.yaprak.reader.formats.fb2.Fb2BookTextExtractor

/** Format/capability routing stays beside the local adapters, never in the search UI. */
internal class LocalBookTextExtractor(context: Context) : BookTextExtractor {
    private val txt = TxtBookTextExtractor(context)
    private val fb2 = Fb2BookTextExtractor(context)
    private val docx = com.yaprak.reader.formats.docx.DocxBookTextExtractor(context)
    private val epub = EpubBookTextExtractor(context)
    private val pdf = PdfBookTextExtractor(context)
    private val images = BookTextExtractor { throw TextExtractionException(TextExtractionFailure.UNSUPPORTED) }
    private fun adapter(book: Book): BookTextExtractor = when (book.format) {
        BookFormat.EPUB -> epub
        BookFormat.PDF -> pdf
        BookFormat.TXT -> txt
        BookFormat.FB2 -> fb2
        BookFormat.DOCX -> docx
        BookFormat.CBZ, BookFormat.CBR -> images
    }
    override fun supports(book: Book) = adapter(book).supports(book)
    override suspend fun extract(book: Book) = adapter(book).extract(book)
    override suspend fun extractChapters(book: Book) = adapter(book).extractChapters(book)
}
