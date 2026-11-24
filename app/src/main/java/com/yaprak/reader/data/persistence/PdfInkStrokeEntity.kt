package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Highlighter strokes on a PDF page (page units; see domain.pdfink). Removed with the book. */
@Entity(tableName = "pdf_ink_strokes", foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"],
    onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["bookId", "contentSha256", "pageIndex"])])
data class PdfInkStrokeEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val contentSha256: String,
    val pageIndex: Int,
    val color: String,
    val mode: String,
    val geometry: String,
    val createdAt: Long,
)

@Dao
interface PdfInkStrokeDao {
    @Query("SELECT * FROM pdf_ink_strokes WHERE bookId = :bookId AND contentSha256 = :contentSha256 ORDER BY createdAt, id")
    fun observe(bookId: String, contentSha256: String): Flow<List<PdfInkStrokeEntity>>

    @Query("SELECT COUNT(*) FROM pdf_ink_strokes WHERE bookId = :bookId AND contentSha256 = :contentSha256")
    suspend fun count(bookId: String, contentSha256: String): Int

    @Insert
    suspend fun insert(stroke: PdfInkStrokeEntity)

    @Query("DELETE FROM pdf_ink_strokes WHERE id = :id AND bookId = :bookId AND contentSha256 = :contentSha256")
    suspend fun delete(id: String, bookId: String, contentSha256: String): Int
}
