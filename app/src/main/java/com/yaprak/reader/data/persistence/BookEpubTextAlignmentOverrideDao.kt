package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookEpubTextAlignmentOverrideDao {
    @Query("""
        SELECT alignment_override.alignment FROM book_epub_text_alignment_overrides AS alignment_override
        INNER JOIN books AS book ON book.id = alignment_override.bookId
        WHERE alignment_override.bookId = :bookId
          AND alignment_override.contentSha256 = :contentSha256
          AND book.sha256 = :contentSha256
          AND book.format IN ('EPUB', 'TXT', 'FB2', 'DOCX')
        LIMIT 1
    """)
    fun observe(bookId: String, contentSha256: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BookEpubTextAlignmentOverrideEntity)

    @Query("DELETE FROM book_epub_text_alignment_overrides WHERE bookId = :bookId AND contentSha256 = :contentSha256")
    suspend fun delete(bookId: String, contentSha256: String)
}
