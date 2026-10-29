package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.*
import kotlinx.coroutines.flow.map

class RoomBookSpacingRepository(private val database: LibraryDatabase) : BookSpacingRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.bookSpacings().observe(bookId, contentSha256).map { stored ->
            if (stored == null) BookSpacingSettings() else {
                check(stored.paragraphValueValid == 1 && stored.wordValueValid == 1) { "Invalid EPUB spacing value" }
                val row = stored.value
                BookSpacingSettings(decode(row.paragraphMode, row.paragraphSpacingPercent),
                    decode(row.wordMode, row.wordSpacingPercent))
                    .also { check(it != BookSpacingSettings()) { "Noncanonical EPUB spacing record" } }
            }
        }

    override suspend fun save(bookId: String, contentSha256: String, choice: BookSpacingSettings) {
        database.withTransaction {
            val book = database.books().findById(bookId)
            check(book?.sha256 == contentSha256 && (book?.format == BookFormat.EPUB.name || book?.format == BookFormat.TXT.name || book?.format == BookFormat.FB2.name || book?.format == BookFormat.DOCX.name)) { "EPUB spacing book mismatch" }
            if (choice == BookSpacingSettings()) database.bookSpacings().delete(bookId, contentSha256)
            else database.bookSpacings().save(BookEpubSpacingOverrideEntity(bookId, contentSha256,
                choice.paragraphs.mode(), (choice.paragraphs as? BookSpacingChoice.Custom)?.percent,
                choice.words.mode(), (choice.words as? BookSpacingChoice.Custom)?.percent))
        }
    }

    private fun BookSpacingChoice.mode(): String = when (this) {
        BookSpacingChoice.Inherit -> "INHERIT"
        BookSpacingChoice.Publisher -> "PUBLISHER"
        is BookSpacingChoice.Custom -> "CUSTOM"
    }
    private fun decode(mode: String, percent: Int?): BookSpacingChoice = when (mode) {
        "INHERIT" -> { check(percent == null); BookSpacingChoice.Inherit }
        "PUBLISHER" -> { check(percent == null); BookSpacingChoice.Publisher }
        "CUSTOM" -> BookSpacingChoice.Custom(checkNotNull(percent))
        else -> error("Invalid EPUB spacing mode")
    }
}
