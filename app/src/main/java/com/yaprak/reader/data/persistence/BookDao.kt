package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class BookWithLastRead(
    @Embedded val book: BookEntity,
    val lastReadAt: Long?,
    @Embedded(prefix = "organization_") val organization: BookOrganizationEntity?,
    @Embedded(prefix = "cover_") val customCover: BookCoverEntity?,
)

data class BookWithOrganization(
    @Embedded val book: BookEntity,
    @Embedded(prefix = "organization_") val organization: BookOrganizationEntity?,
    @Embedded(prefix = "cover_") val customCover: BookCoverEntity?,
)

@Dao
interface BookDao {
    @Query("""SELECT books.*, reading_progress.updatedAt AS lastReadAt,
            book_organizations.bookId AS organization_bookId,
            book_organizations.contentSha256 AS organization_contentSha256,
            book_organizations.readingStatus AS organization_readingStatus,
            book_organizations.favorite AS organization_favorite,
            book_organizations.series AS organization_series,
            book_organizations.genre AS organization_genre,
            book_organizations.tagsJson AS organization_tagsJson,
            book_organizations.collectionsJson AS organization_collectionsJson,
            book_covers.bookId AS cover_bookId,
            book_covers.contentSha256 AS cover_contentSha256,
            book_covers.coverId AS cover_coverId,
            book_covers.storageName AS cover_storageName,
            book_covers.mediaType AS cover_mediaType,
            book_covers.byteSize AS cover_byteSize,
            book_covers.sha256 AS cover_sha256,
            book_covers.width AS cover_width,
            book_covers.height AS cover_height
        FROM books LEFT JOIN reading_progress
            ON reading_progress.bookId = books.id AND reading_progress.contentSha256 = books.sha256
        LEFT JOIN book_organizations
            ON book_organizations.bookId = books.id AND book_organizations.contentSha256 = books.sha256
        LEFT JOIN book_covers
            ON book_covers.bookId = books.id AND book_covers.contentSha256 = books.sha256
        ORDER BY books.importedAt DESC, books.id DESC""")
    fun observeAll(): Flow<List<BookWithLastRead>>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE sha256 = :hash LIMIT 1")
    suspend fun findByHash(hash: String): BookEntity?

    @Query("""SELECT books.*,
            book_organizations.bookId AS organization_bookId,
            book_organizations.contentSha256 AS organization_contentSha256,
            book_organizations.readingStatus AS organization_readingStatus,
            book_organizations.favorite AS organization_favorite,
            book_organizations.series AS organization_series,
            book_organizations.genre AS organization_genre,
            book_organizations.tagsJson AS organization_tagsJson,
            book_organizations.collectionsJson AS organization_collectionsJson,
            book_covers.bookId AS cover_bookId,
            book_covers.contentSha256 AS cover_contentSha256,
            book_covers.coverId AS cover_coverId,
            book_covers.storageName AS cover_storageName,
            book_covers.mediaType AS cover_mediaType,
            book_covers.byteSize AS cover_byteSize,
            book_covers.sha256 AS cover_sha256,
            book_covers.width AS cover_width,
            book_covers.height AS cover_height
        FROM books LEFT JOIN book_organizations
            ON book_organizations.bookId = books.id AND book_organizations.contentSha256 = books.sha256
        LEFT JOIN book_covers
            ON book_covers.bookId = books.id AND book_covers.contentSha256 = books.sha256
        WHERE books.id = :id LIMIT 1""")
    suspend fun findWithOrganizationById(id: String): BookWithOrganization?

    @Query("""SELECT books.*,
            book_organizations.bookId AS organization_bookId,
            book_organizations.contentSha256 AS organization_contentSha256,
            book_organizations.readingStatus AS organization_readingStatus,
            book_organizations.favorite AS organization_favorite,
            book_organizations.series AS organization_series,
            book_organizations.genre AS organization_genre,
            book_organizations.tagsJson AS organization_tagsJson,
            book_organizations.collectionsJson AS organization_collectionsJson,
            book_covers.bookId AS cover_bookId,
            book_covers.contentSha256 AS cover_contentSha256,
            book_covers.coverId AS cover_coverId,
            book_covers.storageName AS cover_storageName,
            book_covers.mediaType AS cover_mediaType,
            book_covers.byteSize AS cover_byteSize,
            book_covers.sha256 AS cover_sha256,
            book_covers.width AS cover_width,
            book_covers.height AS cover_height
        FROM books LEFT JOIN book_organizations
            ON book_organizations.bookId = books.id AND book_organizations.contentSha256 = books.sha256
        LEFT JOIN book_covers
            ON book_covers.bookId = books.id AND book_covers.contentSha256 = books.sha256
        WHERE books.sha256 = :hash LIMIT 1""")
    suspend fun findWithOrganizationByHash(hash: String): BookWithOrganization?

    @Query("SELECT storageName FROM books")
    suspend fun storedFileNames(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE storageName = :storageName AND id != :bookId)")
    suspend fun storageNameUsedByAnother(bookId: String, storageName: String): Boolean

    @Insert
    suspend fun insert(book: BookEntity)

    @Transaction
    suspend fun insertImported(book: BookEntity, organization: BookOrganizationEntity?) {
        if (organization != null) {
            require(organization.bookId == book.id && organization.contentSha256 == book.sha256)
            require(organization.readingStatus == "UNSET" && !organization.favorite)
            organization.toOrganization() // Reject noncanonical fields before inserting any row.
        }
        insert(book)
        if (organization != null) insertOrganization(organization)
    }

    @Query("UPDATE books SET title = :title, authors = :authors WHERE id = :id AND sha256 = :sha256")
    suspend fun updateMetadataExact(id: String, sha256: String, title: String, authors: String?): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrganization(organization: BookOrganizationEntity)

    @Query("SELECT readingStatus FROM book_organizations WHERE bookId = :bookId AND contentSha256 = :hash")
    suspend fun organizationStatus(bookId: String, hash: String): String?

    @Query("SELECT COUNT(*) FROM book_completion_events")
    suspend fun completionEventCount(): Int

    @Insert
    suspend fun insertCompletionEvent(event: BookCompletionEventEntity)

    @Transaction
    suspend fun updateOrganizationExact(organization: BookOrganizationEntity,
        completion: BookCompletionEventEntity? = null): Boolean {
        val book = findById(organization.bookId) ?: return false
        if (book.sha256 != organization.contentSha256) return false
        val transition = organization.readingStatus == "COMPLETED" &&
            organizationStatus(book.id, book.sha256) != "COMPLETED"
        if (transition) {
            val event = requireNotNull(completion) { "Completion event is required" }
            require(event.bookId == book.id && event.contentSha256 == book.sha256)
            com.yaprak.reader.domain.statistics.validateBookCompletionEvent(event.toEvent())
            if (completionEventCount() >= com.yaprak.reader.domain.statistics.MAX_BOOK_COMPLETION_EVENTS)
                throw com.yaprak.reader.domain.statistics.BookCompletionException(
                    com.yaprak.reader.domain.statistics.BookCompletionFailure.LIMIT)
        }
        insertOrganization(organization)
        if (transition) insertCompletionEvent(requireNotNull(completion))
        return true
    }

    @Query("DELETE FROM books WHERE id = :id AND sha256 = :contentSha256")
    suspend fun deleteExact(id: String, contentSha256: String): Int
}
