package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat

@Entity(tableName = "books", indices = [Index(value = ["sha256"], unique = true)])
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val authors: String?,
    val format: String,
    val originalName: String,
    val storageName: String,
    val byteSize: Long,
    val sha256: String,
    val importedAt: Long,
    val pageCount: Int?,
)

internal fun BookEntity.toBook() = Book(
    id, title, authors, BookFormat.valueOf(format), originalName, storageName,
    byteSize, sha256, importedAt, pageCount,
)

internal fun Book.toEntity() = BookEntity(
    id, title, authors, format.name, originalName, storageName,
    byteSize, sha256, importedAt, pageCount,
)
