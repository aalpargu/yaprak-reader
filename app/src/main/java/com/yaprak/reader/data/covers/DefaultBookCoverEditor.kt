package com.yaprak.reader.data.covers

import androidx.room.withTransaction
import com.yaprak.reader.data.persistence.BookCoverEntity
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.toCoverEntity
import com.yaprak.reader.data.storage.BookOperationCoordinator
import com.yaprak.reader.domain.library.BookCoverEditor
import com.yaprak.reader.domain.library.CoverEditFailure
import com.yaprak.reader.domain.library.CoverEditResult
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Immutable file first, exact revision transaction second, old file cleanup last. */
class DefaultBookCoverEditor(
    private val database: LibraryDatabase,
    private val files: LocalCoverFiles,
    private val documents: CoverDocumentProvider,
    private val operations: BookOperationCoordinator,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BookCoverEditor {
    override suspend fun replace(bookId: String, contentSha256: String, expectedCoverId: String?, sourceUri: String): CoverEditResult =
        withContext(io) {
            operations.fileMutex.withLock {
                var staged: StagedCover? = null
                var committed = false
                var moved = false
                try {
                    status(bookId, contentSha256, expectedCoverId)?.let { return@withLock it }
                    val prepared = files.stage({ documents.open(sourceUri) }, UUID.randomUUID().toString())
                    staged = prepared
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        files.commit(prepared); moved = true
                        var previous: BookCoverEntity? = null
                        val rejected = database.withTransaction {
                            status(bookId, contentSha256, expectedCoverId)?.let { return@withTransaction it }
                            previous = database.bookCovers().find(bookId)
                            database.bookCovers().upsert(prepared.cover.toCoverEntity(bookId, contentSha256))
                            null
                        }
                        committed = rejected == null
                        if (rejected != null) rejected
                        else CoverEditResult.Changed(privateFileMayRemain = !cleanOld(previous))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: CoverEditException) {
                    CoverEditResult.Failed(failure.reason)
                } catch (_: SecurityException) {
                    CoverEditResult.Failed(CoverEditFailure.ACCESS)
                } catch (_: FileNotFoundException) {
                    CoverEditResult.Failed(CoverEditFailure.ACCESS)
                } catch (_: Exception) {
                    CoverEditResult.Failed(CoverEditFailure.IO)
                } catch (_: OutOfMemoryError) {
                    CoverEditResult.Failed(CoverEditFailure.IO)
                } finally {
                    if (!committed) staged?.let {
                        if (moved) files.removeCommitted(it.cover.storageName) else it.file.delete()
                    }
                }
            }
        }

    override suspend fun reset(bookId: String, contentSha256: String, expectedCoverId: String?): CoverEditResult = withContext(io) {
        operations.fileMutex.withLock {
            try {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    var previous: BookCoverEntity? = null
                    val rejected = database.withTransaction {
                        status(bookId, contentSha256, expectedCoverId)?.let { return@withTransaction it }
                        previous = database.bookCovers().find(bookId)
                        previous?.let { check(database.bookCovers().deleteExact(bookId, contentSha256, it.coverId) == 1) }
                        null
                    }
                    if (rejected != null) rejected
                    else CoverEditResult.Reset(privateFileMayRemain = !cleanOld(previous))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CoverEditResult.Failed(CoverEditFailure.IO)
            }
        }
    }

    private suspend fun status(bookId: String, hash: String, expectedCoverId: String?): CoverEditResult? {
        val book = database.books().findById(bookId) ?: return CoverEditResult.Missing
        if (book.sha256 != hash) return CoverEditResult.Stale
        val current = database.bookCovers().find(bookId)
        if (current?.coverId != expectedCoverId || (current != null && current.contentSha256 != hash)) return CoverEditResult.Stale
        return null
    }

    private suspend fun cleanOld(previous: BookCoverEntity?): Boolean {
        if (previous == null) return true
        return try {
            if (database.bookCovers().storageNameReferenced(previous.storageName)) true
            else files.removeCommitted(previous.storageName)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
}
