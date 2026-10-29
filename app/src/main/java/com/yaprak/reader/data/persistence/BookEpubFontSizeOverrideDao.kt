package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookEpubFontSizeOverrideDao {
    @Query("""
        SELECT size_override.fontSizePercent FROM book_epub_font_size_overrides AS size_override
        INNER JOIN books AS book ON book.id = size_override.bookId
        WHERE size_override.bookId = :bookId
          AND size_override.contentSha256 = :contentSha256
          AND book.sha256 = :contentSha256
          AND book.format IN ('EPUB', 'TXT', 'FB2', 'DOCX')
        LIMIT 1
    """)
    fun observe(bookId: String, contentSha256: String): Flow<Int?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BookEpubFontSizeOverrideEntity)

    @Query("DELETE FROM book_epub_font_size_overrides WHERE bookId = :bookId AND contentSha256 = :contentSha256")
    suspend fun delete(bookId: String, contentSha256: String)
}
