package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookFontSizeRepository
import kotlinx.coroutines.flow.map

class RoomBookFontSizeRepository(private val database: LibraryDatabase) : BookFontSizeRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.bookFontSizes().observe(bookId, contentSha256).map { saved ->
            saved?.also { check(it in 75..200 && it % 5 == 0) { "Invalid saved EPUB font size" } }
        }

    override suspend fun save(bookId: String, contentSha256: String, fontSizePercent: Int?) {
        require(fontSizePercent == null || fontSizePercent in 75..200 && fontSizePercent % 5 == 0) {
            "EPUB font size must be 75-200% in steps of five"
        }
        database.withTransaction {
            val book = database.books().findById(bookId)
            check(book?.sha256 == contentSha256 && (book?.format == BookFormat.EPUB.name || book?.format == BookFormat.TXT.name || book?.format == BookFormat.FB2.name || book?.format == BookFormat.DOCX.name)) {
                "EPUB font size book mismatch"
            }
            if (fontSizePercent == null) {
                database.bookFontSizes().delete(bookId, contentSha256)
            } else {
                database.bookFontSizes().save(BookEpubFontSizeOverrideEntity(bookId, contentSha256, fontSizePercent))
            }
        }
    }
}
