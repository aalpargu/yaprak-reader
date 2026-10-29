package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yaprak.reader.domain.library.BookCover

@Entity(tableName = "book_covers", foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"],
    onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["coverId"], unique = true), Index(value = ["storageName"], unique = true)])
data class BookCoverEntity(
    @PrimaryKey val bookId: String,
    val contentSha256: String,
    val coverId: String,
    val storageName: String,
    val mediaType: String,
    val byteSize: Long,
    val sha256: String,
    val width: Int,
    val height: Int,
)

fun BookCoverEntity.toCover() = BookCover(coverId, storageName, mediaType, byteSize, sha256, width, height)

fun BookCover.toCoverEntity(bookId: String, contentSha256: String): BookCoverEntity {
    validated()
    return BookCoverEntity(bookId, contentSha256, id, storageName, mediaType, byteSize, sha256, width, height)
}
