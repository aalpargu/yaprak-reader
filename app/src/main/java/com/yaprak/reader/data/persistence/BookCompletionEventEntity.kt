package com.yaprak.reader.data.persistence

import androidx.room.*
import com.yaprak.reader.domain.statistics.BookCompletionEvent

@Entity(tableName = "book_completion_events", foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"],
    onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["bookId", "contentSha256", "completedAt"])])
data class BookCompletionEventEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val contentSha256: String,
    val completedAt: Long,
    val localDate: String,
    val zoneId: String,
)
internal fun BookCompletionEventEntity.toEvent() =
    BookCompletionEvent(id, bookId, contentSha256, completedAt, localDate, zoneId)