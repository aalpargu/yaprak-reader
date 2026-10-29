package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookEpubSpacingOverrideDao {
    @Query("""
        SELECT spacing.*,
          CASE WHEN spacing.paragraphSpacingPercent IS NULL OR
            (typeof(spacing.paragraphSpacingPercent) = 'integer' AND
             spacing.paragraphSpacingPercent BETWEEN 0 AND 200 AND spacing.paragraphSpacingPercent % 10 = 0)
            THEN 1 ELSE 0 END AS paragraphValueValid,
          CASE WHEN spacing.wordSpacingPercent IS NULL OR
            (typeof(spacing.wordSpacingPercent) = 'integer' AND
             spacing.wordSpacingPercent BETWEEN 0 AND 100 AND spacing.wordSpacingPercent % 10 = 0)
            THEN 1 ELSE 0 END AS wordValueValid
        FROM book_epub_spacing_overrides AS spacing
        INNER JOIN books AS book ON book.id = spacing.bookId
        WHERE spacing.bookId = :bookId AND spacing.contentSha256 = :contentSha256
          AND book.sha256 = :contentSha256 AND book.format IN ('EPUB', 'TXT', 'FB2', 'DOCX')
        LIMIT 1
    """)
    fun observe(bookId: String, contentSha256: String): Flow<BookEpubSpacingOverrideRow?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BookEpubSpacingOverrideEntity)

    @Query("DELETE FROM book_epub_spacing_overrides WHERE bookId = :bookId AND contentSha256 = :contentSha256")
    suspend fun delete(bookId: String, contentSha256: String)
}

/** Validate raw SQLite values before Room's Int cursor read can truncate a float or large Long. */
data class BookEpubSpacingOverrideRow(
    @Embedded val value: BookEpubSpacingOverrideEntity,
    val paragraphValueValid: Int,
    val wordValueValid: Int,
)
