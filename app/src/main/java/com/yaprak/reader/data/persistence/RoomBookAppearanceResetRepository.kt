package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookAppearanceResetRepository

class RoomBookAppearanceResetRepository(private val database: LibraryDatabase) : BookAppearanceResetRepository {
    override suspend fun reset(bookId: String, contentSha256: String) {
        database.withTransaction {
            val book = database.books().findById(bookId)
            check(book?.sha256 == contentSha256 && (book?.format == BookFormat.EPUB.name || book?.format == BookFormat.TXT.name || book?.format == BookFormat.FB2.name || book?.format == BookFormat.DOCX.name)) {
                "EPUB appearance book mismatch"
            }
            database.bookThemes().delete(bookId, contentSha256)
            database.bookFontSizes().delete(bookId, contentSha256)
            database.bookFonts().delete(bookId, contentSha256)
            database.bookMargins().delete(bookId, contentSha256)
            database.bookLineHeights().delete(bookId, contentSha256)
            database.bookSpacings().delete(bookId, contentSha256)
            database.bookTextAlignments().delete(bookId, contentSha256)
            database.bookReadingModes().delete(bookId, contentSha256)
        }
    }
}
