package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedWordDao {
    @Query("""
        SELECT saved.* FROM saved_words AS saved
        INNER JOIN books AS book ON book.id = saved.bookId
        WHERE saved.bookId = :bookId AND saved.contentSha256 = :contentSha256
          AND book.sha256 = :contentSha256
        ORDER BY saved.createdAt DESC, saved.id DESC
    """)
    fun observe(bookId: String, contentSha256: String): Flow<List<SavedWordEntity>>

    @Insert
    suspend fun add(entity: SavedWordEntity)

    @Query("""
        UPDATE saved_words SET english = :english, turkish = :turkish, revision = revision + 1
        WHERE id = :id AND bookId = :bookId AND contentSha256 = :contentSha256 AND revision < 9223372036854775807
    """)
    suspend fun update(bookId: String, contentSha256: String, id: String, english: String, turkish: String): Int

    @Query("DELETE FROM saved_words WHERE id = :id AND bookId = :bookId AND contentSha256 = :contentSha256")
    suspend fun remove(bookId: String, contentSha256: String, id: String): Int
}
