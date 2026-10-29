package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookFontRepository
import com.yaprak.reader.domain.settings.ReadingFont
import kotlinx.coroutines.flow.map

class RoomBookFontRepository(private val database: LibraryDatabase) : BookFontRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.bookFonts().observe(bookId, contentSha256).map { saved ->
            saved?.let { name ->
                ReadingFont.entries.firstOrNull { it.name == name }
                    ?: error("Unknown saved EPUB font family")
            }
        }

    override suspend fun save(bookId: String, contentSha256: String, font: ReadingFont?) {
        database.withTransaction {
            val book = database.books().findById(bookId)
            check(book?.sha256 == contentSha256 && (book?.format == BookFormat.EPUB.name || book?.format == BookFormat.TXT.name || book?.format == BookFormat.FB2.name || book?.format == BookFormat.DOCX.name)) {
                "EPUB font family book mismatch"
            }
            if (font == null) database.bookFonts().delete(bookId, contentSha256)
            else database.bookFonts().save(BookEpubFontOverrideEntity(bookId, contentSha256, font.name))
        }
    }
}
