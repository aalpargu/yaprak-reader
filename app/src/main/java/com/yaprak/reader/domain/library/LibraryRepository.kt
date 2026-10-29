package com.yaprak.reader.domain.library

import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun observeBooks(): Flow<List<Book>>
    suspend fun findById(id: String): Book?
    suspend fun findByHash(sha256: String): Book?
    suspend fun storedFileNames(): Set<String>
    suspend fun add(book: Book)
    /** New import only: persist declared labels with the book atomically, never infer reading state. */
    suspend fun addImported(book: Book, initialOrganization: BookOrganization) {
        if (initialOrganization != BookOrganization())
            throw UnsupportedOperationException("Imported organization is not supported")
        add(book)
    }
    /** Returns true only when the current content hash still identifies the book. */
    suspend fun updateMetadata(id: String, sha256: String, title: String, authors: String?): Boolean
    /** Writes only for the current content hash; unsupported implementations fail explicitly. */
    suspend fun updateOrganization(id: String, sha256: String, organization: BookOrganization): Boolean =
        throw UnsupportedOperationException("Book organization is not supported")
}
