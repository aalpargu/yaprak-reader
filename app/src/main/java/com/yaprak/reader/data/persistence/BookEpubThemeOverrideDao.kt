package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookEpubThemeOverrideDao {
    @Query("""
        SELECT theme_override.theme FROM book_epub_theme_overrides AS theme_override
        INNER JOIN books AS book ON book.id = theme_override.bookId
        WHERE theme_override.bookId = :bookId
          AND theme_override.contentSha256 = :contentSha256
          AND book.sha256 = :contentSha256
          AND book.format IN ('EPUB', 'TXT', 'FB2', 'DOCX')
        LIMIT 1
    """)
    fun observe(bookId: String, contentSha256: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BookEpubThemeOverrideEntity)

    @Query("DELETE FROM book_epub_theme_overrides WHERE bookId = :bookId AND contentSha256 = :contentSha256")
    suspend fun delete(bookId: String, contentSha256: String)
}
