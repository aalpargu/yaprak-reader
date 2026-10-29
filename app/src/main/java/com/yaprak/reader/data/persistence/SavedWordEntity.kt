package com.yaprak.reader.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_words",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["bookId", "contentSha256"])],
)
data class SavedWordEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val contentSha256: String,
    val english: String,
    val turkish: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
)
