package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EpubPassageDao {
    @Query("""
        SELECT passage.* FROM epub_passages AS passage
        INNER JOIN books AS book ON book.id = passage.bookId
        WHERE passage.bookId = :bookId AND passage.contentSha256 = :contentSha256
          AND book.sha256 = :contentSha256 AND book.format = 'EPUB'
          AND passage.anchorVersion = 1
        ORDER BY passage.createdAt DESC, passage.id DESC
    """)
    fun observe(bookId: String, contentSha256: String): Flow<List<EpubPassageEntity>>

    @Insert
    suspend fun add(entity: EpubPassageEntity)

    @Query("""
        SELECT EXISTS(SELECT 1 FROM epub_passages
          WHERE bookId = :bookId AND contentSha256 = :contentSha256
            AND anchorVersion = 1 AND resourceHref = :resourceHref
            AND nodeTextSha256 = :nodeTextSha256
            AND startOffset = :startOffset AND endOffset = :endOffset)
    """)
    suspend fun exists(bookId: String, contentSha256: String, resourceHref: String,
        nodeTextSha256: String, startOffset: Int, endOffset: Int): Boolean

    @Query("""
        DELETE FROM epub_passages
        WHERE id = :id AND bookId = :bookId AND contentSha256 = :contentSha256
    """)
    suspend fun remove(bookId: String, contentSha256: String, id: String): Int
}
