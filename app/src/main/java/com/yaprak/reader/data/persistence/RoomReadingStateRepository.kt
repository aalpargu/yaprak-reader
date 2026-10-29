package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.reader.ReadingStateRepository

class RoomReadingStateRepository(private val database: LibraryDatabase) : ReadingStateRepository {
    override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? =
        database.reading().resume(bookId, contentSha256)?.let {
            ReadingLocation(it.locatorJson, it.resourceHref, it.locatorVersion)
        }

    override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) {
        require(snapshot.location.locatorVersion == 1)
        require(snapshot.location.locatorJson.length <= 64_000)
        require(snapshot.spans.size <= 256)
        database.withTransaction {
            val book = database.books().findById(bookId)
            check(book?.sha256 == contentSha256) { "Book content changed" }
            check(book?.format == "EPUB" || snapshot.spans.isEmpty()) { "This format has no original EPUB DOM coverage" }
            val location = snapshot.location
            database.reading().save(ReadingProgressEntity(bookId, contentSha256, location.locatorVersion,
                location.locatorJson, location.resourceHref, System.currentTimeMillis()))
            val spans = snapshot.spans.map {
                require(it.resourceHref == location.resourceHref && it.extractionVersion == 1)
                require(it.startOffset >= 0 && it.endOffset > it.startOffset)
                require(it.nodePath.matches(Regex("[0-9]+(?:/[0-9]+)*")) && it.nodePath.length <= 1024)
                require(it.textSha256.matches(Regex("[a-f0-9]{64}")))
                ObservedTextEntity(bookId, contentSha256, it.extractionVersion, it.resourceHref,
                    it.nodePath, it.startOffset, it.endOffset, it.textSha256)
            }
            // Never connect two observations, infer a chapter prefix, or fill skipped gaps.
            database.reading().observe(spans)
        }
    }
}
