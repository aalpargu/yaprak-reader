package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookTextAlignmentRepository
import com.yaprak.reader.domain.settings.ReadingTextAlignment
import kotlinx.coroutines.flow.map

class RoomBookTextAlignmentRepository(private val database: LibraryDatabase) : BookTextAlignmentRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.bookTextAlignments().observe(bookId, contentSha256).map { saved ->
            saved?.let { name ->
                ReadingTextAlignment.entries.firstOrNull { it.name == name }
                    ?: error("Unknown saved EPUB text alignment")
            }
        }

    override suspend fun save(bookId: String, contentSha256: String, alignment: ReadingTextAlignment?) {
        database.withTransaction {
            val book = database.books().findById(bookId)
            check(book?.sha256 == contentSha256 && (book?.format == BookFormat.EPUB.name || book?.format == BookFormat.TXT.name || book?.format == BookFormat.FB2.name || book?.format == BookFormat.DOCX.name)) {
                "EPUB text alignment book mismatch"
            }
            if (alignment == null) database.bookTextAlignments().delete(bookId, contentSha256)
            else database.bookTextAlignments().save(BookEpubTextAlignmentOverrideEntity(bookId, contentSha256, alignment.name))
        }
    }
}
