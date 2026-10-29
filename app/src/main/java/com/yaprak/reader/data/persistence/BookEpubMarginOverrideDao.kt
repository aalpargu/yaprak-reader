package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookEpubMarginOverrideDao {
    @Query("""
        SELECT margin_override.marginsPercent FROM book_epub_margin_overrides AS margin_override
        INNER JOIN books AS book ON book.id = margin_override.bookId
        WHERE margin_override.bookId = :bookId
          AND margin_override.contentSha256 = :contentSha256
          AND book.sha256 = :contentSha256
          AND book.format IN ('EPUB', 'TXT', 'FB2', 'DOCX')
        LIMIT 1
    """)
    fun observe(bookId: String, contentSha256: String): Flow<Int?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BookEpubMarginOverrideEntity)

    @Query("DELETE FROM book_epub_margin_overrides WHERE bookId = :bookId AND contentSha256 = :contentSha256")
    suspend fun delete(bookId: String, contentSha256: String)
}
