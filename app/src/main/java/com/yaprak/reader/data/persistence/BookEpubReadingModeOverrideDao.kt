package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookEpubReadingModeOverrideDao {
    @Query("""
        SELECT mode_override.mode FROM book_epub_reading_mode_overrides AS mode_override
        INNER JOIN books AS book ON book.id = mode_override.bookId
        WHERE mode_override.bookId = :bookId
          AND mode_override.contentSha256 = :contentSha256
          AND book.sha256 = :contentSha256
          AND book.format IN ('EPUB', 'TXT', 'FB2', 'DOCX')
        LIMIT 1
    """)
    fun observe(bookId: String, contentSha256: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BookEpubReadingModeOverrideEntity)

    @Query("DELETE FROM book_epub_reading_mode_overrides WHERE bookId = :bookId AND contentSha256 = :contentSha256")
    suspend fun delete(bookId: String, contentSha256: String)
}
