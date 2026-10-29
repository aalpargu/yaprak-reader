package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epub_passages",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["bookId", "contentSha256", "anchorVersion", "resourceHref",
        "nodeTextSha256", "startOffset", "endOffset"], unique = true)],
)
data class EpubPassageEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val contentSha256: String,
    val anchorVersion: Int,
    val resourceHref: String,
    val nodeTextSha256: String,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val createdAt: Long,
    val highlightColor: String? = null,
    val attachedNote: String? = null,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
)
