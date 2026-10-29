package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.bookmarks.*
import com.yaprak.reader.domain.reader.ReadingLocation
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomBookmarkRepository(private val database: LibraryDatabase) : BookmarkRepository {
    override fun observe(bookId: String, contentSha256: String) = database.bookmarks().observe(bookId, contentSha256).map { rows ->
        rows.map { Bookmark(it.id, BookmarkTarget(it.label, ReadingLocation(it.locatorJson, it.resourceHref, it.locatorVersion)), it.createdAt) }
    }

    override suspend fun add(bookId: String, contentSha256: String, target: BookmarkTarget) = withContext(Dispatchers.IO) {
        require(target.label.isNotBlank() && target.label.length <= 200)
        val location = target.location
        require(location.locatorVersion == 1 && location.locatorJson.length in 1..64_000)
        require(location.resourceHref.isNotBlank() && location.resourceHref.length <= 4096)
        val keyBytes = "${location.locatorVersion}\n${location.resourceHref}\n${location.locatorJson}".toByteArray(Charsets.UTF_8)
        val key = MessageDigest.getInstance("SHA-256").digest(keyBytes).joinToString("") { "%02x".format(it) }
        database.withTransaction {
            verifyBook(bookId, contentSha256)
            database.bookmarks().add(BookmarkEntity(UUID.randomUUID().toString(), bookId, contentSha256, key,
                target.label, location.locatorJson, location.resourceHref, location.locatorVersion, System.currentTimeMillis()))
        }
    }

    override suspend fun rename(bookId: String, contentSha256: String, bookmarkId: String, label: String) {
        val trimmed = label.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 200)
        database.withTransaction {
            verifyBook(bookId, contentSha256)
            check(database.bookmarks().rename(bookId, contentSha256, bookmarkId, trimmed) == 1) { "Bookmark missing" }
        }
    }

    override suspend fun remove(bookId: String, contentSha256: String, bookmarkId: String) {
        database.withTransaction {
            verifyBook(bookId, contentSha256)
            database.bookmarks().remove(bookId, contentSha256, bookmarkId)
        }
    }

    private suspend fun verifyBook(bookId: String, hash: String) {
        val book = database.books().findById(bookId)
        check(book?.sha256 == hash) { "Bookmark book mismatch" }
    }
}
