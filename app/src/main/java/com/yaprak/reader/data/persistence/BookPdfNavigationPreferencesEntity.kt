package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Additive navigation choice: existing crop/fit/spread rows are never rewritten by migration. */
@Entity(
    tableName = "book_pdf_navigation_preferences",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class BookPdfNavigationPreferencesEntity(
    @PrimaryKey val bookId: String,
    val contentSha256: String,
    val mode: String,
)
