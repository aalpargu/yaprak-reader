package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "pdf_annotations", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "contentSha256", "selectionKey"], unique = true)])
data class PdfAnnotationEntity(@PrimaryKey val id: String, val bookId: String, val contentSha256: String,
    val anchorVersion: Int, val pageIndex: Int, val anchorJson: String, val anchorKey: String, val selectionKey: String,
    val text: String, val createdAt: Long, val highlightColor: String?, val attachedNote: String?,
    @androidx.room.ColumnInfo(defaultValue = "0") val revision: Long = 0)
