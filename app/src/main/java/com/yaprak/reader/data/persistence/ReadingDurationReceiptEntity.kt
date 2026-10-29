package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.yaprak.reader.domain.statistics.ReadingDurationReceipt

@Entity(tableName = "reading_duration_receipts", foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"],
    onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["bookId", "contentSha256", "localDate"])])
data class ReadingDurationReceiptEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val contentSha256: String,
    val localDate: String,
    val zoneId: String,
    val startedAt: Long,
    val endedAt: Long,
    val elapsedMillis: Long,
)

internal fun ReadingDurationReceiptEntity.toReceipt() = ReadingDurationReceipt(
    id, bookId, contentSha256, localDate, zoneId, startedAt, endedAt, elapsedMillis,
)

@Dao
interface ReadingDurationReceiptDao {
    @Query("SELECT * FROM reading_duration_receipts ORDER BY id")
    suspend fun all(): List<ReadingDurationReceiptEntity>

    @Insert
    suspend fun insert(receipt: ReadingDurationReceiptEntity)
}
