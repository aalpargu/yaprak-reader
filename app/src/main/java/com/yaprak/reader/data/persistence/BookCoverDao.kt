package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Upsert
import androidx.room.Query

@Dao
interface BookCoverDao {
    @Query("SELECT * FROM book_covers WHERE bookId = :bookId LIMIT 1")
    suspend fun find(bookId: String): BookCoverEntity?

    @Query("SELECT storageName FROM book_covers")
    suspend fun storedFileNames(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM book_covers WHERE storageName = :storageName)")
    suspend fun storageNameReferenced(storageName: String): Boolean

    @Upsert
    suspend fun upsert(cover: BookCoverEntity)

    @Query("DELETE FROM book_covers WHERE bookId = :bookId AND contentSha256 = :contentSha256 AND coverId = :coverId")
    suspend fun deleteExact(bookId: String, contentSha256: String, coverId: String): Int
}
