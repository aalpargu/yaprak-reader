package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookMarginsRepository
import kotlinx.coroutines.flow.map

class RoomBookMarginsRepository(private val database: LibraryDatabase) : BookMarginsRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.bookMargins().observe(bookId, contentSha256).map { saved ->
            saved?.also { check(it in 50..200 && it % 10 == 0) { "Invalid saved EPUB margins" } }
        }

    override suspend fun save(bookId: String, contentSha256: String, marginsPercent: Int?) {
        require(marginsPercent == null || marginsPercent in 50..200 && marginsPercent % 10 == 0) {
            "EPUB margins must be 50-200% in steps of ten"
        }
        database.withTransaction {
            val book = database.books().findById(bookId)
            check(book?.sha256 == contentSha256 && (book?.format == BookFormat.EPUB.name || book?.format == BookFormat.TXT.name || book?.format == BookFormat.FB2.name || book?.format == BookFormat.DOCX.name)) {
                "EPUB margins book mismatch"
            }
            if (marginsPercent == null) {
                database.bookMargins().delete(bookId, contentSha256)
            } else {
                database.bookMargins().save(BookEpubMarginOverrideEntity(bookId, contentSha256, marginsPercent))
            }
        }
    }
}
