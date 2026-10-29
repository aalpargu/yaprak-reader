package com.yaprak.reader.data.persistence

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND contentSha256 = :hash ORDER BY createdAt DESC, id")
    fun observe(bookId: String, hash: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(bookmark: BookmarkEntity)

    @Query("UPDATE bookmarks SET label = :label WHERE bookId = :bookId AND contentSha256 = :hash AND id = :id")
    suspend fun rename(bookId: String, hash: String, id: String, label: String): Int

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND contentSha256 = :hash AND id = :id")
    suspend fun remove(bookId: String, hash: String, id: String)
}
