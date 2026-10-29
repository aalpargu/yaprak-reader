package com.yaprak.reader.data.persistence

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookOrganization
import com.yaprak.reader.domain.library.LibraryRepository
import kotlinx.coroutines.flow.map

class RoomLibraryRepository(private val dao: BookDao,
    private val now: () -> java.time.ZonedDateTime = { java.time.ZonedDateTime.now() },
    private val completionId: () -> String = { java.util.UUID.randomUUID().toString() },
) : LibraryRepository {
    override suspend fun findById(id: String) = dao.findWithOrganizationById(id)?.let { row ->
        row.book.toBook().copy(organization = row.organization?.toOrganization() ?: BookOrganization(), customCover = row.customCover?.toCover())
    }
    override fun observeBooks() = dao.observeAll().map { rows ->
        rows.map { it.book.toBook().copy(lastReadAt = it.lastReadAt,
            organization = it.organization?.toOrganization() ?: BookOrganization(), customCover = it.customCover?.toCover()) }
    }
    override suspend fun findByHash(sha256: String) = dao.findWithOrganizationByHash(sha256)?.let { row ->
        row.book.toBook().copy(organization = row.organization?.toOrganization() ?: BookOrganization(), customCover = row.customCover?.toCover())
    }
    override suspend fun storedFileNames() = dao.storedFileNames().toSet()
    override suspend fun add(book: Book) = dao.insert(book.toEntity())
    override suspend fun addImported(book: Book, initialOrganization: BookOrganization) {
        val value = initialOrganization.normalized()
        require(value.readingStatus == com.yaprak.reader.domain.library.BookReadingStatus.UNSET && !value.favorite)
        dao.insertImported(book.toEntity(),
            if (value == BookOrganization()) null else value.toOrganizationEntity(book.id, book.sha256))
    }
    override suspend fun updateMetadata(id: String, sha256: String, title: String, authors: String?) =
        dao.updateMetadataExact(id, sha256, title, authors) == 1
    override suspend fun updateOrganization(id: String, sha256: String, organization: BookOrganization): Boolean {
        val row = organization.toOrganizationEntity(id, sha256)
        val completion = if (row.readingStatus == "COMPLETED") {
            val time = now()
            BookCompletionEventEntity(completionId(), id, sha256, time.toInstant().toEpochMilli(),
                time.toLocalDate().toString(), time.zone.id)
        } else null
        // The DAO resolves the previous status and commits status + event in one actual transaction.
        return dao.updateOrganizationExact(row, completion)
    }
}
