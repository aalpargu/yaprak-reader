package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookThemeRepository
import com.yaprak.reader.domain.settings.ReadingTheme
import kotlinx.coroutines.flow.map

class RoomBookThemeRepository(private val database: LibraryDatabase) : BookThemeRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.bookThemes().observe(bookId, contentSha256).map { saved ->
            saved?.let { name ->
                ReadingTheme.entries.firstOrNull { it.name == name }
                    ?: error("Unknown saved EPUB theme")
            }
        }

    override suspend fun save(bookId: String, contentSha256: String, theme: ReadingTheme?) {
        database.withTransaction {
            val book = database.books().findById(bookId)
            check(book?.sha256 == contentSha256 && (book?.format == BookFormat.EPUB.name || book?.format == BookFormat.TXT.name || book?.format == BookFormat.FB2.name || book?.format == BookFormat.DOCX.name)) {
                "EPUB theme book mismatch"
            }
            if (theme == null) {
                database.bookThemes().delete(bookId, contentSha256)
            } else {
                database.bookThemes().save(BookEpubThemeOverrideEntity(bookId, contentSha256, theme.name))
            }
        }
    }
}
