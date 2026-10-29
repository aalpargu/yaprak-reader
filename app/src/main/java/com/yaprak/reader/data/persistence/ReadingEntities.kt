package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(tableName = "reading_progress", primaryKeys = ["bookId"], foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)
])
data class ReadingProgressEntity(
    val bookId: String, val contentSha256: String, val locatorVersion: Int,
    val locatorJson: String, val resourceHref: String, val updatedAt: Long,
)

@Entity(tableName = "observed_text", primaryKeys = ["bookId", "contentSha256", "extractionVersion", "resourceHref", "nodePath", "startOffset", "endOffset", "textSha256"], foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)
])
data class ObservedTextEntity(
    val bookId: String, val contentSha256: String, val extractionVersion: Int,
    val resourceHref: String, val nodePath: String, val startOffset: Int, val endOffset: Int,
    val textSha256: String,
)
