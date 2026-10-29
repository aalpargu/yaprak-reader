package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.notes.BookNote
import com.yaprak.reader.domain.notes.BookNoteRepository
import com.yaprak.reader.domain.notes.MAX_BOOK_NOTE_LENGTH
import java.util.UUID
import kotlinx.coroutines.flow.map

class RoomBookNoteRepository(private val database: LibraryDatabase) : BookNoteRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.bookNotes().observe(bookId, contentSha256).map { rows ->
            rows.map { BookNote(it.id, it.text, it.createdAt, it.pdfPageIndex) }
        }

    override suspend fun add(bookId: String, contentSha256: String, text: String) {
        val trimmed = validatedText(text)
        database.withTransaction {
            verifyBook(bookId, contentSha256)
            database.bookNotes().add(BookNoteEntity(
                UUID.randomUUID().toString(), bookId, contentSha256, trimmed, System.currentTimeMillis(),
            ))
        }
    }

    override suspend fun addAtPdfPage(bookId: String, contentSha256: String, text: String, pageIndex: Int) {
        val trimmed = validatedText(text)
        require(pageIndex >= 0) { "PDF page index must be nonnegative" }
        database.withTransaction {
            val book = database.books().findById(bookId)
            check(book != null && book.sha256 == contentSha256 && book.format == "PDF" &&
                book.pageCount != null && pageIndex < book.pageCount) {
                "PDF page note book or page mismatch"
            }
            database.bookNotes().add(BookNoteEntity(
                UUID.randomUUID().toString(), bookId, contentSha256, trimmed, System.currentTimeMillis(), pageIndex,
            ))
        }
    }

    override suspend fun update(bookId: String, contentSha256: String, id: String, text: String) {
        val trimmed = validatedText(text)
        database.withTransaction {
            verifyBook(bookId, contentSha256)
            check(database.bookNotes().update(bookId, contentSha256, id, trimmed) == 1) {
                "Book note missing"
            }
        }
    }

    override suspend fun remove(bookId: String, contentSha256: String, id: String) {
        database.withTransaction {
            verifyBook(bookId, contentSha256)
            check(database.bookNotes().remove(bookId, contentSha256, id) == 1) { "Book note missing" }
        }
    }

    private suspend fun verifyBook(bookId: String, contentSha256: String) {
        check(database.books().findById(bookId)?.sha256 == contentSha256) { "Book note book mismatch" }
    }

    private fun validatedText(text: String): String = text.trim().also { trimmed ->
        require(trimmed.length in 1..MAX_BOOK_NOTE_LENGTH) {
            "Book note must contain 1–$MAX_BOOK_NOTE_LENGTH UTF-16 code units"
        }
    }
}
