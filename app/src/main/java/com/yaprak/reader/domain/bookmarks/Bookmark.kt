package com.yaprak.reader.domain.bookmarks

import com.yaprak.reader.domain.reader.ReadingLocation
import kotlinx.coroutines.flow.Flow

data class BookmarkTarget(val label: String, val location: ReadingLocation)
data class Bookmark(val id: String, val target: BookmarkTarget, val createdAt: Long)

interface BookmarkRepository {
    fun observe(bookId: String, contentSha256: String): Flow<List<Bookmark>>
    suspend fun add(bookId: String, contentSha256: String, target: BookmarkTarget)
    suspend fun rename(bookId: String, contentSha256: String, bookmarkId: String, label: String)
    suspend fun remove(bookId: String, contentSha256: String, bookmarkId: String)
}
