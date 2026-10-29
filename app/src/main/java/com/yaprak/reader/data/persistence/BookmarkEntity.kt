package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "contentSha256", "locationKey"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val contentSha256: String,
    val locationKey: String,
    val label: String,
    val locatorJson: String,
    val resourceHref: String,
    val locatorVersion: Int,
    val createdAt: Long,
)
