package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReadingDao {
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId AND contentSha256 = :hash LIMIT 1")
    suspend fun resume(bookId: String, hash: String): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: ReadingProgressEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun observe(spans: List<ObservedTextEntity>)

    @Query("SELECT * FROM observed_text WHERE bookId = :bookId AND contentSha256 = :hash ORDER BY resourceHref, nodePath")
    suspend fun observed(bookId: String, hash: String): List<ObservedTextEntity>
}
