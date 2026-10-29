package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "book_epub_line_spacing_overrides",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class BookEpubLineSpacingOverrideEntity(
    @PrimaryKey val bookId: String,
    val contentSha256: String,
    /** Null in an existing row means an explicit publisher choice. */
    val lineHeightPercent: Int?,
)
