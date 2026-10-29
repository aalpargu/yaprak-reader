package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "book_pdf_view_preferences",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class BookPdfViewPreferencesEntity(
    @PrimaryKey val bookId: String,
    val contentSha256: String,
    val horizontalPercent: Int,
    val verticalPercent: Int,
    val fitMode: String,
    /** Kept as an integer so a malformed value cannot be coerced into a Boolean. */
    val doublePagePreferred: Int,
)
