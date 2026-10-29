package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "book_notes",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["bookId", "contentSha256"])],
)
data class BookNoteEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val contentSha256: String,
    val text: String,
    val createdAt: Long,
    val pdfPageIndex: Int? = null,
)
