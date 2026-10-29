package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookNoteDao {
    @Query("""
        SELECT note.* FROM book_notes AS note
        INNER JOIN books AS book ON book.id = note.bookId
        WHERE note.bookId = :bookId AND note.contentSha256 = :contentSha256
          AND book.sha256 = :contentSha256
        ORDER BY note.createdAt DESC, note.id DESC
    """)
    fun observe(bookId: String, contentSha256: String): Flow<List<BookNoteEntity>>

    @Insert
    suspend fun add(entity: BookNoteEntity)

    @Query("UPDATE book_notes SET text = :text WHERE id = :id AND bookId = :bookId AND contentSha256 = :contentSha256")
    suspend fun update(bookId: String, contentSha256: String, id: String, text: String): Int

    @Query("DELETE FROM book_notes WHERE id = :id AND bookId = :bookId AND contentSha256 = :contentSha256")
    suspend fun remove(bookId: String, contentSha256: String, id: String): Int
}
