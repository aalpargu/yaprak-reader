package com.yaprak.reader.domain.notes

import kotlinx.coroutines.flow.Flow

/** A user-written note for a book, optionally tied to one rendered PDF page. */
data class BookNote(
    val id: String,
    val text: String,
    val createdAt: Long,
    val pdfPageIndex: Int? = null,
)

const val MAX_BOOK_NOTE_LENGTH = 2000

interface BookNoteRepository {
    fun observe(bookId: String, contentSha256: String): Flow<List<BookNote>>
    suspend fun add(bookId: String, contentSha256: String, text: String)
    suspend fun addAtPdfPage(bookId: String, contentSha256: String, text: String, pageIndex: Int) {
        throw UnsupportedOperationException("PDF page notes are not supported by this repository")
    }
    suspend fun update(bookId: String, contentSha256: String, id: String, text: String)
    suspend fun remove(bookId: String, contentSha256: String, id: String)
}
