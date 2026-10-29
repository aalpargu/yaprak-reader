package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookEpubLineSpacingOverrideDao {
    @Query("""
        SELECT spacing_override.* FROM book_epub_line_spacing_overrides AS spacing_override
        INNER JOIN books AS book ON book.id = spacing_override.bookId
        WHERE spacing_override.bookId = :bookId
          AND spacing_override.contentSha256 = :contentSha256
          AND book.sha256 = :contentSha256
          AND book.format IN ('EPUB', 'TXT', 'FB2', 'DOCX')
        LIMIT 1
    """)
    fun observe(bookId: String, contentSha256: String): Flow<BookEpubLineSpacingOverrideEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BookEpubLineSpacingOverrideEntity)

    @Query("DELETE FROM book_epub_line_spacing_overrides WHERE bookId = :bookId AND contentSha256 = :contentSha256")
    suspend fun delete(bookId: String, contentSha256: String)
}
