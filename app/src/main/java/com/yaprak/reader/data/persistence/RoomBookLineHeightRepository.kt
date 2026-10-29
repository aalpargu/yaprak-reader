package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookLineHeightChoice
import com.yaprak.reader.domain.settings.BookLineHeightRepository
import kotlinx.coroutines.flow.map

class RoomBookLineHeightRepository(private val database: LibraryDatabase) : BookLineHeightRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.bookLineHeights().observe(bookId, contentSha256).map { saved ->
            when {
                saved == null -> BookLineHeightChoice.Inherit
                saved.lineHeightPercent == null -> BookLineHeightChoice.Publisher
                else -> BookLineHeightChoice.Custom(saved.lineHeightPercent)
            }
        }

    override suspend fun save(bookId: String, contentSha256: String, choice: BookLineHeightChoice) {
        database.withTransaction {
            val book = database.books().findById(bookId)
            check(book?.sha256 == contentSha256 && (book?.format == BookFormat.EPUB.name || book?.format == BookFormat.TXT.name || book?.format == BookFormat.FB2.name || book?.format == BookFormat.DOCX.name)) {
                "EPUB line height book mismatch"
            }
            when (choice) {
                BookLineHeightChoice.Inherit -> database.bookLineHeights().delete(bookId, contentSha256)
                BookLineHeightChoice.Publisher -> database.bookLineHeights().save(
                    BookEpubLineSpacingOverrideEntity(bookId, contentSha256, null)
                )
                is BookLineHeightChoice.Custom -> database.bookLineHeights().save(
                    BookEpubLineSpacingOverrideEntity(bookId, contentSha256, choice.percent)
                )
            }
        }
    }
}
