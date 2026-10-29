package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookReadingModeRepository
import com.yaprak.reader.domain.settings.BookReadingMode
import kotlinx.coroutines.flow.map

class RoomBookReadingModeRepository(private val database: LibraryDatabase) : BookReadingModeRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.bookReadingModes().observe(bookId, contentSha256).map { saved ->
            saved?.let { name ->
                BookReadingMode.entries.firstOrNull { it.name == name }
                    ?: error("Unknown saved reading mode")
            }
        }

    override suspend fun save(bookId: String, contentSha256: String, mode: BookReadingMode?) {
        database.withTransaction {
            val book = database.books().findById(bookId)
            check(book?.sha256 == contentSha256 && (book?.format == BookFormat.EPUB.name || book?.format == BookFormat.TXT.name || book?.format == BookFormat.FB2.name || book?.format == BookFormat.DOCX.name)) {
                "Reflowable reading mode book mismatch"
            }
            if (mode == null) database.bookReadingModes().delete(bookId, contentSha256)
            else database.bookReadingModes().save(BookEpubReadingModeOverrideEntity(bookId, contentSha256, mode.name))
        }
    }
}
