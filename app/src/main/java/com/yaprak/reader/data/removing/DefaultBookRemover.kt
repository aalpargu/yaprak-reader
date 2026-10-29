package com.yaprak.reader.data.removing

import androidx.room.withTransaction
import com.yaprak.reader.data.covers.CoverFileCleanup
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.storage.BookOperationCoordinator
import com.yaprak.reader.data.storage.BookFileCleanup
import com.yaprak.reader.domain.library.BookRemovalResult
import com.yaprak.reader.domain.library.BookRemover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A committed DB deletion wins; a failed file unlink is reported and later import recovery retries it. */
class DefaultBookRemover(
    private val database: LibraryDatabase,
    private val files: BookFileCleanup,
    private val operations: BookOperationCoordinator,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val covers: CoverFileCleanup? = null,
) : BookRemover {
    private data class CommittedRemoval(val storageName: String, val fileShared: Boolean, val coverStorageName: String?)

    override suspend fun remove(bookId: String, contentSha256: String): BookRemovalResult = withContext(io) {
        operations.fileMutex.withLock {
            if (!operations.beginRemoval(bookId)) return@withLock BookRemovalResult.InUse
            try {
                // Once started, finish both steps even if the screen is destroyed. SQLite
                // cascades progress, observations, bookmarks, notes, words and preferences.
                withContext(NonCancellable) finish@{
                    val removed = try {
                        database.withTransaction {
                            val row = database.books().findById(bookId)
                                ?.takeIf { it.sha256 == contentSha256 }
                                ?: return@withTransaction null
                            // A damaged database may have two rows pointing at one file.
                            val fileShared = database.books().storageNameUsedByAnother(bookId, row.storageName)
                            val coverStorageName = database.bookCovers().find(bookId)?.storageName
                            check(database.books().deleteExact(bookId, contentSha256) == 1)
                            CommittedRemoval(row.storageName, fileShared, coverStorageName)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        return@finish BookRemovalResult.Failed
                    }
                    if (removed == null) BookRemovalResult.Missing
                    else {
                        val cleaned = try { removed.fileShared || files.removeCommitted(removed.storageName) }
                            catch (_: Exception) { false }
                        val coverCleaned = try {
                            val coverName = removed.coverStorageName
                            coverName == null || database.bookCovers().storageNameReferenced(coverName) ||
                                (covers?.removeCommitted(coverName) == true)
                        } catch (_: Exception) { false }
                        BookRemovalResult.Removed(privateFileMayRemain = !cleaned || !coverCleaned)
                    }
                }
            } finally {
                operations.endRemoval(bookId)
            }
        }
    }
}
