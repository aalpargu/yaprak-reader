package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(tableName = "book_cbz_view_preferences", foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE,
)])
data class BookCbzViewPreferencesEntity(
    @PrimaryKey val bookId: String,
    val contentSha256: String,
    val doublePagePreferred: Int,
)
