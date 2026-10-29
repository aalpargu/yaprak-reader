package com.yaprak.reader.reader

import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.LibraryRepository

class FormatReaderLoader(
    private val library: LibraryRepository,
    private val epub: ReaderLoader,
    private val pdf: ReaderLoader,
    private val txt: ReaderLoader? = null,
    private val cbz: ReaderLoader? = null,
    private val fb2: ReaderLoader? = null,
    private val docx: ReaderLoader? = null,
) : ReaderLoader {
    override suspend fun open(bookId: String): ReaderSession =
        when (library.findById(bookId)?.format ?: throw ReaderOpenException(ReaderFailure.MISSING)) {
            BookFormat.EPUB -> epub.open(bookId)
            BookFormat.PDF -> pdf.open(bookId)
            BookFormat.TXT -> txt?.open(bookId) ?: throw ReaderOpenException(ReaderFailure.UNSUPPORTED)
            BookFormat.FB2 -> fb2?.open(bookId) ?: throw ReaderOpenException(ReaderFailure.UNSUPPORTED)
            BookFormat.CBZ, BookFormat.CBR -> cbz?.open(bookId) ?: throw ReaderOpenException(ReaderFailure.UNSUPPORTED)
            BookFormat.DOCX -> docx?.open(bookId) ?: throw ReaderOpenException(ReaderFailure.UNSUPPORTED)
        }
}
