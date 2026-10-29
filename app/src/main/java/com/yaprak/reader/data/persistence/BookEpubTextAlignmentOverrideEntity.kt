package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "book_epub_text_alignment_overrides",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class BookEpubTextAlignmentOverrideEntity(
    @PrimaryKey val bookId: String,
    val contentSha256: String,
    val alignment: String,
)
