package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(tableName = "book_epub_spacing_overrides", foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE,
)])
data class BookEpubSpacingOverrideEntity(
    @PrimaryKey val bookId: String,
    val contentSha256: String,
    val paragraphMode: String,
    val paragraphSpacingPercent: Int?,
    val wordMode: String,
    val wordSpacingPercent: Int?,
)
